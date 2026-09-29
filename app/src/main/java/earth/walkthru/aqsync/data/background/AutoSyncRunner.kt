package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.data.BackgroundSyncResult
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One way the worker may reach a logger, in the order [AutoSyncPolicy.linksFor] returns them. */
sealed interface AutoSyncLink {
    /** TCP to the address mDNS just resolved for the device's station, with the stored token. */
    data class Lan(val target: LanDevice) : AutoSyncLink

    /** Direct `connectGatt` to the bonded address, no scan, no pairing dialog. */
    data object Ble : AutoSyncLink
}

/**
 * Everything the auto-sync worker needs from the rest of the app, behind one interface so the
 * decision logic in [AutoSyncRunner] runs in a plain JVM test with the fake device standing in
 * for Room, mDNS, the Bluetooth adapter and the repository.
 */
interface AutoSyncGateway {
    /** Known devices with the Auto-sync switch on, most recently connected first. */
    suspend fun autoSyncDevices(): List<KnownDevice>

    /** One known device by Bluetooth address, switch on or off ("Sync now" targets the connected device). */
    suspend fun knownDevice(address: String): KnownDevice?

    /** Every known device, switch on or off, most recently connected first ("Collect from all loggers"). */
    suspend fun allKnownDevices(): List<KnownDevice>

    /** Address of the session the app currently holds (user is on a device screen), or null. */
    fun interactiveSessionAddress(): String?

    /** Skip only this device; the controller performs an atomic check again before opening. */
    fun isDeviceOwned(address: String): Boolean = interactiveSessionAddress() == address

    /**
     * Browse `_aqsync._tcp` for up to [timeoutMs] (return early once every station in [stations]
     * is resolved). Empty when the LAN permission is missing or discovery fails. Keyed by station.
     */
    suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, LanDevice>

    /** Whether this phone holds a Bluetooth bond for [address]. */
    fun isBonded(address: String): Boolean

    /** Connect over [link], LIST, pull the missing files, disconnect. Never throws for a device that is simply absent. */
    suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult
}

/** Pure "which links, in which order" rule; shared by the runner and its tests. */
object AutoSyncPolicy {
    /**
     * LAN first when the device's station is on the network *and* a token is stored (the same rule
     * the interactive connect uses), then Bluetooth if — and only if — the phone is bonded: an
     * unbonded device would need the passkey dialog, which does not exist in a worker.
     */
    fun linksFor(device: KnownDevice, lan: Map<String, LanDevice>, bonded: Boolean, lanOnly: Boolean = false): List<AutoSyncLink> {
        val links = ArrayList<AutoSyncLink>(2)
        val station = device.station
        if (station != null && device.hasLanToken) {
            lan[station]?.takeIf { it.isConnectable }?.let { links += AutoSyncLink.Lan(it) }
        }
        if (bonded && !lanOnly) links += AutoSyncLink.Ble
        return links
    }
}

/** What happened to one device in one run. */
data class DeviceRunReport(
    val device: KnownDevice,
    /** Links actually tried, in order; empty when the device was skipped before any connect. */
    val tried: List<AutoSyncLink>,
    /** Result of the first link that got as far as a session, or the last attempt's result. Null when nothing was tried. */
    val result: BackgroundSyncResult?,
    /** Set when [AutoSyncGateway.sync] threw: a transient condition (IO) rather than an outcome. */
    val error: Throwable? = null,
    /** Why the device was left alone, when [tried] is empty. */
    val skipped: String? = null,
) {
    val newFiles: Int get() = result?.newFiles ?: 0
    val newBytes: Long get() = result?.newBytes ?: 0L
    val failures: Int get() = result?.failures ?: 0
}

/** One run over every auto-sync device. */
data class AutoSyncReport(val devices: List<DeviceRunReport>) {
    val newFiles: Int get() = devices.sumOf { it.newFiles }
    val newBytes: Long get() = devices.sumOf { it.newBytes }
    val failures: Int get() = devices.sumOf { it.failures }

    /** Post the summary notification only when something happened worth telling the owner. */
    val shouldNotify: Boolean get() = newFiles > 0 || failures > 0

    /** Name for the summary: the one device that had news, or a count. */
    val summaryDeviceName: String
        get() {
            val withNews = devices.filter { it.newFiles > 0 || it.failures > 0 }
            return when (withNews.size) {
                0 -> "logger"
                1 -> withNews[0].result?.deviceName ?: withNews[0].device.name ?: "logger"
                else -> "${withNews.size} loggers"
            }
        }

    /**
     * A transfer that broke off (link lost, exception) deserves a backed-off retry inside this
     * period. A device that was simply not reachable does not: the next period tries again.
     */
    val transientFailure: Boolean get() = devices.any { it.error != null || it.result?.incomplete == true }
}

/**
 * The worker's decision logic, free of Android: which devices, which links, in which order, and
 * what to tell the owner afterwards. [SyncWorker] wraps it in WorkManager plumbing.
 *
 * Per device: skip if the app holds a session (interactive use wins, one link at a time); otherwise
 * try [AutoSyncPolicy.linksFor] in order until one link produces a session — a link that failed to
 * connect (`skipped`) falls through to the next, an attempted sync ends the device. Exceptions from
 * a link are recorded and the next link is still tried. Cancellation propagates.
 */
class AutoSyncRunner(
    private val gateway: AutoSyncGateway,
    private val lanBrowseMs: Long = LAN_BROWSE_MS,
    private val log: (String) -> Unit = {},
    private val maxConcurrentDevices: Int = 2,
) {
    /**
     * [onlyAddress] = a manual "Sync now in background" for that one device, whether or not its
     * Auto-sync switch is on; [allKnown] = a manual "Collect from all loggers" over every known device
     * (a maintainer walking a site); otherwise the periodic run over every device with the switch on.
     * In every case each device is tried in turn over LAN, then its Bluetooth bond — one link at a time.
     * [lanOnly] (the Wi-Fi-arrival trigger) leaves Bluetooth out: a Wi-Fi join says nothing about range.
     */
    suspend fun run(onlyAddress: String? = null, allKnown: Boolean = false, lanOnly: Boolean = false): AutoSyncReport {
        val devices = when {
            onlyAddress != null -> listOfNotNull(gateway.knownDevice(onlyAddress))
            allKnown -> gateway.allKnownDevices()
            else -> gateway.autoSyncDevices()
        }
        if (devices.isEmpty()) return AutoSyncReport(emptyList())

        val stations = devices.mapNotNull { d -> d.station?.takeIf { d.hasLanToken } }.toSet()
        val lan = if (stations.isEmpty()) emptyMap() else gateway.browseLan(stations, lanBrowseMs)

        require(maxConcurrentDevices > 0)
        val permits = Semaphore(maxConcurrentDevices)
        return coroutineScope {
            AutoSyncReport(
                devices.distinctBy { it.address }.map { device ->
                    async {
                        permits.withPermit {
                            if (gateway.isDeviceOwned(device.address)) {
                                DeviceRunReport(device, emptyList(), null, skipped = "the app has an interactive session with this device")
                            } else {
                                val links = AutoSyncPolicy.linksFor(device, lan, gateway.isBonded(device.address), lanOnly)
                                if (links.isEmpty()) {
                                    val why = if (lanOnly) "not on the LAN (Bluetooth not tried on a Wi-Fi trigger)" else "not on the LAN and not bonded"
                                    DeviceRunReport(device, emptyList(), null, skipped = why)
                                } else {
                                    runDevice(device, links)
                                }
                            }
                        }
                    }
                }.awaitAll()
            )
        }
    }

    private suspend fun runDevice(device: KnownDevice, links: List<AutoSyncLink>): DeviceRunReport {
        val tried = ArrayList<AutoSyncLink>(links.size)
        var last: BackgroundSyncResult? = null
        var error: Throwable? = null
        for (link in links) {
            tried += link
            val result = try {
                gateway.sync(device, link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("auto-sync ${device.name ?: device.address} over ${link.label()}: ${e::class.simpleName}: ${e.message}")
                error = e
                continue
            }
            last = result
            if (result.attempted) {
                log(
                    "auto-sync ${device.name ?: device.address} over ${link.label()}: ${result.newFiles} new file(s), " +
                        "${result.newBytes} B, ${result.failures} failed${if (result.incomplete) ", incomplete" else ""}"
                )
                return DeviceRunReport(device, tried, result)
            }
            log("auto-sync ${device.name ?: device.address} over ${link.label()}: ${result.skipped}")
            if (gateway.isDeviceOwned(device.address)) {
                // The user opened a device meanwhile: interactive use wins, do not try the other link.
                return DeviceRunReport(device, tried, result, skipped = result.skipped)
            }
        }
        return DeviceRunReport(device, tried, last, error = error, skipped = if (last == null && error == null) "no link" else last?.skipped)
    }

    private fun AutoSyncLink.label() = when (this) {
        is AutoSyncLink.Lan -> "LAN"
        AutoSyncLink.Ble -> "Bluetooth"
    }

    companion object {
        /** mDNS browse budget before falling back to Bluetooth; the CoreS3 answers in well under a second when present. */
        const val LAN_BROWSE_MS = 10_000L
    }
}
