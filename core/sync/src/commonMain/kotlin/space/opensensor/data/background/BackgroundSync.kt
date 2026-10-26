package space.opensensor.data.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import space.opensensor.data.BackgroundSyncResult

/*
 * Background sync, shared by Android (WorkManager, `SyncWorker`) and iOS (BGTaskScheduler,
 * `ios/App/BackgroundSync.swift`). Moved from Android's `AutoSyncRunner` for #23 with identical
 * behaviour: which devices are due, which links in which order, one pass over them, and what to
 * tell the owner afterwards. The platforms supply the device list, LAN discovery, the bond check
 * and the actual transfer through [BackgroundSyncGateway]. Android keeps its old names as
 * typealiases (`AutoSyncRunner`, `AutoSyncGateway`, `AutoSyncReport`).
 */

/** What the runner reads from a stored device. Android's `KnownDevice`, iOS's known record. */
interface BackgroundSyncDevice {
    /** Bluetooth address on Android, peripheral identifier on iOS. */
    val address: String
    val name: String?
    val station: String?

    /** Whether a LAN token is stored, so a LAN connect can authenticate. */
    val hasLanToken: Boolean
}

/** A logger mDNS just resolved on the local network. */
interface BackgroundLanTarget {
    /** Resolved with a host and port, so a TCP connect can start. */
    val isConnectable: Boolean
}

/** One way the run may reach a logger, in the order [BackgroundSyncPolicy.linksFor] returns them. */
sealed interface BackgroundSyncLink<out T : BackgroundLanTarget> {
    /** TCP to the address mDNS just resolved for the device's station, with the stored token. */
    data class Lan<out T : BackgroundLanTarget>(val target: T) : BackgroundSyncLink<T>

    /** Direct connect to the bonded peripheral, no scan, no pairing dialog. */
    data object Ble : BackgroundSyncLink<Nothing>
}

/**
 * Everything a background run needs from the platform, behind one interface so the decision logic
 * in [BackgroundSyncRunner] runs in a plain host test with a fake gateway.
 */
interface BackgroundSyncGateway<D : BackgroundSyncDevice, T : BackgroundLanTarget> {
    /** Known devices with the Auto-sync switch on, most recently connected first. */
    suspend fun autoSyncDevices(): List<D>

    /** One known device by address, switch on or off ("Sync now" targets the connected device). */
    suspend fun knownDevice(address: String): D?

    /** Every known device, switch on or off, most recently connected first ("Collect from all loggers"). */
    suspend fun allKnownDevices(): List<D>

    /** Address of the session the app currently holds (user is on a device screen), or null. */
    fun interactiveSessionAddress(): String?

    /** Skip only this device; the platform performs an atomic check again before opening. */
    fun isDeviceOwned(address: String): Boolean = interactiveSessionAddress() == address

    /**
     * Browse `_ossync._tcp` for up to [timeoutMs] (return early once every station in [stations]
     * is resolved). Empty when the LAN permission is missing or discovery fails. Keyed by station.
     */
    suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, T>

    /** Whether this phone holds a Bluetooth bond for [address]. */
    fun isBonded(address: String): Boolean

    /** Connect over [link], LIST, pull the missing files, disconnect. Never throws for a device that is simply absent. */
    suspend fun sync(device: D, link: BackgroundSyncLink<T>): BackgroundSyncResult
}

/** Pure rules shared by the runner, both platforms' schedulers and their tests. */
object BackgroundSyncPolicy {
    /** mDNS browse budget before falling back to Bluetooth; the CoreS3 answers in well under a second when present. */
    const val LAN_BROWSE_MS = 10_000L

    /** Period between scheduled runs. WorkManager's minimum, and the earliest begin date iOS asks for. */
    const val PERIOD_MS = 15 * 60_000L

    /** First retry delay after a transfer broke off; doubles per retry. */
    const val BACKOFF_MS = 5 * 60_000L

    /** Retries inside one period before waiting for the next one. */
    const val MAX_RETRIES = 2

    /** At most this many devices sync at once. */
    const val MAX_CONCURRENT_DEVICES = 2

    /**
     * LAN first when the device's station is on the network *and* a token is stored (the same rule
     * the interactive connect uses), then Bluetooth if, and only if, the phone is bonded. An
     * unbonded device would need the passkey dialog, which does not exist in the background.
     */
    fun <T : BackgroundLanTarget> linksFor(device: BackgroundSyncDevice, lan: Map<String, T>, bonded: Boolean, lanOnly: Boolean = false): List<BackgroundSyncLink<T>> {
        val links = ArrayList<BackgroundSyncLink<T>>(2)
        val station = device.station
        if (station != null && device.hasLanToken) {
            lan[station]?.takeIf { it.isConnectable }?.let { links += BackgroundSyncLink.Lan(it) }
        }
        if (bonded && !lanOnly) links += BackgroundSyncLink.Ble
        return links
    }

    /** Stations worth browsing for: devices that could authenticate a LAN connect. */
    fun lanStations(devices: List<BackgroundSyncDevice>): Set<String> = devices.mapNotNull { d -> d.station?.takeIf { d.hasLanToken } }.toSet()

    /** Whether a run that ended with [report] deserves a backed-off retry after [attempt] tries. */
    fun shouldRetry(report: BackgroundSyncReport<*>, attempt: Int): Boolean = report.transientFailure && attempt < MAX_RETRIES

    /** Delay before retry number [attempt] (0 based), exponential from [BACKOFF_MS]. */
    fun backoffMs(attempt: Int): Long = BACKOFF_MS shl attempt.coerceIn(0, 16)

    /**
     * Whether a device last synced in the background at [lastRunMs] is due at [nowMs]. Never run
     * means due. A clock that moved backwards counts as due rather than stalling for a period.
     */
    fun isDue(lastRunMs: Long?, nowMs: Long, periodMs: Long = PERIOD_MS): Boolean {
        if (lastRunMs == null) return true
        val since = nowMs - lastRunMs
        return since < 0 || since >= periodMs
    }

    /** The subset of [devices] due at [nowMs], order kept. [lastRun] maps address to the last run. */
    fun <D : BackgroundSyncDevice> dueDevices(devices: List<D>, lastRun: Map<String, Long>, nowMs: Long, periodMs: Long = PERIOD_MS): List<D> =
        devices.filter { isDue(lastRun[it.address], nowMs, periodMs) }
}

/** What happened to one device in one run. */
data class DeviceRunReport<out D : BackgroundSyncDevice>(
    val device: D,
    /** Links actually tried, in order; empty when the device was skipped before any connect. */
    val tried: List<BackgroundSyncLink<*>>,
    /** Result of the first link that got as far as a session, or the last attempt's result. Null when nothing was tried. */
    val result: BackgroundSyncResult?,
    /** Set when [BackgroundSyncGateway.sync] threw: a transient condition (IO) rather than an outcome. */
    val error: Throwable? = null,
    /** Why the device was left alone, when [tried] is empty. */
    val skipped: String? = null,
) {
    val newFiles: Int get() = result?.newFiles ?: 0
    val newBytes: Long get() = result?.newBytes ?: 0L
    val failures: Int get() = result?.failures ?: 0
}

/** One run over the chosen devices. */
data class BackgroundSyncReport<out D : BackgroundSyncDevice>(val devices: List<DeviceRunReport<D>>) {
    val newFiles: Int get() = devices.sumOf { it.newFiles }
    val newBytes: Long get() = devices.sumOf { it.newBytes }
    val failures: Int get() = devices.sumOf { it.failures }

    /** Post the summary notification only when something happened worth telling the owner. */
    val shouldNotify: Boolean get() = newFiles > 0 || failures > 0

    /** Devices that had news, for a summary that names them. */
    val devicesWithNews: List<DeviceRunReport<D>> get() = devices.filter { it.newFiles > 0 || it.failures > 0 }

    /** Name for the summary: the one device that had news, or a count. */
    val summaryDeviceName: String
        get() {
            val withNews = devicesWithNews
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
 * The background run's decision logic, free of any platform: which devices, which links, in which
 * order, and what to tell the owner afterwards.
 *
 * Per device: skip if the app holds a session (interactive use wins, one link at a time); otherwise
 * try [BackgroundSyncPolicy.linksFor] in order until one link produces a session. A link that failed
 * to connect (`skipped`) falls through to the next, an attempted sync ends the device. Exceptions
 * from a link are recorded and the next link is still tried. Cancellation propagates.
 */
class BackgroundSyncRunner<D : BackgroundSyncDevice, T : BackgroundLanTarget>(
    private val gateway: BackgroundSyncGateway<D, T>,
    private val lanBrowseMs: Long = BackgroundSyncPolicy.LAN_BROWSE_MS,
    private val log: (String) -> Unit = {},
    private val maxConcurrentDevices: Int = BackgroundSyncPolicy.MAX_CONCURRENT_DEVICES,
) {
    /**
     * [onlyAddress] = a triggered run for that one device (presence or advert), whether or not its
     * Auto-sync switch is on; [allKnown] = a manual "Collect from all loggers" over every known device;
     * otherwise the periodic run over every device with the switch on. [devices], when given,
     * replaces that choice (iOS passes the due subset). In every case each device is tried in turn
     * over LAN, then its Bluetooth bond, one link at a time. [lanOnly] (the Wi-Fi-arrival trigger)
     * leaves Bluetooth out: a Wi-Fi join says nothing about range.
     */
    suspend fun run(onlyAddress: String? = null, allKnown: Boolean = false, lanOnly: Boolean = false, devices: List<D>? = null): BackgroundSyncReport<D> {
        val chosen = devices ?: when {
            onlyAddress != null -> listOfNotNull(gateway.knownDevice(onlyAddress))
            allKnown -> gateway.allKnownDevices()
            else -> gateway.autoSyncDevices()
        }
        if (chosen.isEmpty()) return BackgroundSyncReport(emptyList())

        val stations = BackgroundSyncPolicy.lanStations(chosen)
        val lan = if (stations.isEmpty()) emptyMap() else gateway.browseLan(stations, lanBrowseMs)

        require(maxConcurrentDevices > 0)
        val permits = Semaphore(maxConcurrentDevices)
        return coroutineScope {
            BackgroundSyncReport(
                chosen.distinctBy { it.address }.map { device ->
                    async {
                        permits.withPermit {
                            if (gateway.isDeviceOwned(device.address)) {
                                DeviceRunReport(device, emptyList(), null, skipped = "the app has an interactive session with this device")
                            } else {
                                val links = BackgroundSyncPolicy.linksFor(device, lan, gateway.isBonded(device.address), lanOnly)
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

    private suspend fun runDevice(device: D, links: List<BackgroundSyncLink<T>>): DeviceRunReport<D> {
        val tried = ArrayList<BackgroundSyncLink<T>>(links.size)
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

    private fun BackgroundSyncLink<*>.label() = when (this) {
        is BackgroundSyncLink.Lan -> "LAN"
        BackgroundSyncLink.Ble -> "Bluetooth"
    }
}
