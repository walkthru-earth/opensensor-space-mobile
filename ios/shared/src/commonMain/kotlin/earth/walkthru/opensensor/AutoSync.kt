package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.BackgroundSyncResult
import earth.walkthru.aqsync.data.background.BackgroundLanTarget
import earth.walkthru.aqsync.data.background.BackgroundSyncDevice
import earth.walkthru.aqsync.data.background.BackgroundSyncGateway
import earth.walkthru.aqsync.data.background.BackgroundSyncLink
import earth.walkthru.aqsync.data.background.BackgroundSyncPolicy
import earth.walkthru.aqsync.data.background.BackgroundSyncReport
import earth.walkthru.aqsync.data.background.BackgroundSyncRunner
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.units.formatFixed
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.IosAuthorization
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.autosync_progress_title
import earth.walkthru.opensensor.strings.resources.autosync_sensors
import earth.walkthru.opensensor.strings.resources.notif_device
import earth.walkthru.opensensor.strings.resources.notif_failures
import earth.walkthru.opensensor.strings.resources.notif_problems
import earth.walkthru.opensensor.strings.resources.notif_synced
import earth.walkthru.opensensor.strings.resources.notif_verified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.resume

/*
 * iOS Auto-sync (#23). The per device switch, the record of when each sensor last synced in the
 * background, and the bridge that runs the shared BackgroundSyncRunner (`:core:sync`) over the Swift
 * adapters. Swift owns BGTaskScheduler, Core Bluetooth, Network.framework and UserNotifications.
 * Kotlin decides which sensors are due, which link to try and what the summary says. Only peripheral
 * ids, names, stations and counts cross. Never a PIN or token. Call everything on the main thread.
 */

/**
 * One sensor with Auto-sync on. [lastRunMs] is the last background sync that finished cleanly, which
 * decides when it is due. [lastWakeMs] is the last Bluetooth wake for it, whatever the outcome, which
 * decides when it may get a pending connect again.
 */
internal data class AutoSyncEntry(val peripheral: String, val lastRunMs: Long?, val lastWakeMs: Long? = null)

/** Where the switch states live. UserDefaults on the phone, memory in tests. */
internal interface AutoSyncStorage {
    fun load(): String?
    fun save(text: String)
}

/** The pure rules behind the switch store, so the tests read them without UserDefaults. */
internal object IosAutoSync {
    /** One sensor per line, peripheral id, the last clean run and the last wake, tab separated. */
    fun encode(entries: List<AutoSyncEntry>): String =
        entries.joinToString("\n") { "${it.peripheral}\t${it.lastRunMs?.toString().orEmpty()}\t${it.lastWakeMs?.toString().orEmpty()}" }

    /** Lines that do not parse are dropped. Ids compare without case. A line without the wake field reads as never woken. */
    fun decode(text: String?): List<AutoSyncEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size !in 2..3 || !IosKnown.isPeripheral(f[0])) return@mapNotNull null
        AutoSyncEntry(f[0].uppercase(), f[1].toLongOrNull(), f.getOrNull(2)?.toLongOrNull())
    }.distinctBy { it.peripheral }.toList()

    fun set(entries: List<AutoSyncEntry>, peripheral: String, on: Boolean): List<AutoSyncEntry> {
        val id = peripheral.uppercase()
        val rest = entries.filterNot { it.peripheral == id }
        return if (on) rest + (entries.firstOrNull { it.peripheral == id } ?: AutoSyncEntry(id, null)) else rest
    }

    fun markRun(entries: List<AutoSyncEntry>, peripherals: Set<String>, nowMs: Long): List<AutoSyncEntry> {
        val ids = peripherals.map { it.uppercase() }.toSet()
        return entries.map { if (it.peripheral in ids) it.copy(lastRunMs = nowMs) else it }
    }

    fun markWake(entries: List<AutoSyncEntry>, peripheral: String, nowMs: Long): List<AutoSyncEntry> =
        entries.map { if (it.peripheral.equals(peripheral, ignoreCase = true)) it.copy(lastWakeMs = nowMs) else it }

    /**
     * The sensors that get a pending Bluetooth connect. Due ones only, and none woken within the
     * period, so a sensor in range whose steps keep skipping (busy, held, lost bond) wakes the app
     * at most once a period. [except] is the sensor whose wake is finishing, never armed again inside it.
     */
    fun armable(entries: List<AutoSyncEntry>, nowMs: Long, except: String?): List<String> = entries.filter {
        !it.peripheral.equals(except, ignoreCase = true) &&
            BackgroundSyncPolicy.isDue(it.lastRunMs, nowMs) &&
            BackgroundSyncPolicy.isDue(it.lastWakeMs, nowMs)
    }.map { it.peripheral }

    /** Only a step that ran to the end with no error counts as a run. A lost or broken one stays due for the retry. */
    fun finished(report: BackgroundSyncReport<*>): Set<String> = report.devices.filter { d ->
        val r = d.result
        d.error == null && r != null && r.attempted && !r.incomplete
    }.map { it.device.address }.toSet()

    /** Byte counts for the summary, binary units with a dot, as Files prints them. */
    fun bytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> formatFixed(bytes / 1024.0, 1) + " KiB"
        bytes < 1024L * 1024 * 1024 -> formatFixed(bytes / 1048576.0, 2) + " MiB"
        else -> formatFixed(bytes / 1073741824.0, 2) + " GiB"
    }
}

/**
 * The per device Auto-sync switch. Off by default. Turning it on asks for notification permission
 * the first time only, from that tap ([AutoSyncSwitch]). The switch turns on whatever the answer, a
 * denial only hides the summary. [changed] tells Swift to schedule the background task and arm the
 * pending Bluetooth connects again.
 */
class AutoSyncSettings internal constructor(
    private val storage: AutoSyncStorage,
    private val now: () -> Long,
    private val notificationStatus: () -> Long,
    private val requestNotifications: (done: () -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    private val entries = MutableStateFlow(IosAutoSync.decode(storage.load()))

    internal val state: StateFlow<List<AutoSyncEntry>> get() = entries.asStateFlow()

    fun isOn(peripheral: String): Boolean = entries.value.any { it.peripheral.equals(peripheral, ignoreCase = true) }

    /** Peripheral ids with the switch on, for the pending connects Swift arms. */
    fun enabledPeripherals(): List<String> = entries.value.map { it.peripheral }

    /** Whether [peripheral] last synced in the background a full period ago, or never. */
    fun isDue(peripheral: String): Boolean =
        BackgroundSyncPolicy.isDue(entries.value.firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }?.lastRunMs, now())

    /** Peripheral ids that may get a pending connect now. See [IosAutoSync.armable]. */
    fun armable(except: String?): List<String> = IosAutoSync.armable(entries.value, now(), except)

    /** Whether a Bluetooth wake for [peripheral] should run. The same rule as [armable], for a connect armed earlier. */
    fun shouldWake(peripheral: String): Boolean = peripheral.uppercase() in armable(null)

    internal fun lastRuns(): Map<String, Long> = entries.value.mapNotNull { e -> e.lastRunMs?.let { e.peripheral to it } }.toMap()

    internal fun set(peripheral: String, on: Boolean) {
        save(IosAutoSync.set(entries.value, peripheral, on))
        changed()
    }

    internal fun markRun(peripherals: Set<String>) {
        if (peripherals.isNotEmpty()) save(IosAutoSync.markRun(entries.value, peripherals, now()))
    }

    internal fun markWake(peripheral: String) {
        if (isOn(peripheral)) save(IosAutoSync.markWake(entries.value, peripheral, now()))
    }

    /** A forgotten sensor loses its switch too. */
    fun forget(peripheral: String) {
        if (isOn(peripheral)) set(peripheral, false)
    }

    internal fun notificationAccess(): AccessStatus = IosAuthorization.notifications(notificationStatus())

    internal fun askNotifications(done: () -> Unit) = requestNotifications(done)

    private fun save(list: List<AutoSyncEntry>) {
        entries.value = list
        storage.save(IosAutoSync.encode(list))
    }
}

/**
 * The schedule numbers the Swift scheduler uses, in seconds, from [BackgroundSyncPolicy], so both
 * platforms wait the same period and back off the same way.
 */
object AutoSyncSchedule {
    val periodSeconds: Double get() = BackgroundSyncPolicy.PERIOD_MS / 1000.0
    val maxRetries: Int get() = BackgroundSyncPolicy.MAX_RETRIES

    /** The delay before retry number [attempt], 0 based. */
    fun retrySeconds(attempt: Int): Double = BackgroundSyncPolicy.backoffMs(attempt) / 1000.0
}

/** One known sensor as the shared runner sees it. [address] is the peripheral id. */
internal data class IosSyncDevice(
    override val address: String,
    override val name: String?,
    override val station: String?,
    override val hasLanToken: Boolean,
) : BackgroundSyncDevice

/** A Wi-Fi sensor the browse resolved, by its service name. */
internal data class IosLanTarget(val serviceName: String, val station: String) : BackgroundLanTarget {
    override val isConnectable: Boolean get() = serviceName.isNotBlank()
}

/** What one Swift sync step reports back. [skipped] is non-null when nothing was attempted. Never a token. */
class BackgroundStepResult(
    val newFiles: Int,
    val newBytes: Long,
    val failures: Int,
    val skipped: String?,
    val incomplete: Boolean,
)

/**
 * The step results Swift reports, built here so the mapping from a sync end is shared and tested.
 * A step that sent nothing is skipped, so the runner tries the next link and the sensor stays due.
 */
object BackgroundSteps {
    fun skipped(reason: String): BackgroundStepResult = BackgroundStepResult(0, 0, 0, reason, false)

    /** The link ended before the engine reported. Verified files stay, a backed-off retry follows. */
    fun lost(): BackgroundStepResult = BackgroundStepResult(0, 0, 0, null, true)

    /** The engine's result. [bytes] is what this sync received, without resumed bytes. */
    fun ended(result: SyncResult, bytes: Long): BackgroundStepResult = when (result.end) {
        SyncEnd.IN_USE -> skipped("in use")
        SyncEnd.BUSY -> skipped("busy")
        SyncEnd.AUTH_FAILED -> skipped("wifi access refused")
        else -> {
            // A fault outside one file counts once, so the summary reports it.
            val fault = result.end == SyncEnd.FAILED || result.end == SyncEnd.STORAGE_FAILED
            val failures = if (fault) maxOf(result.notSaved, 1) else result.notSaved
            val incomplete = result.end == SyncEnd.LINK_LOST || result.end == SyncEnd.STOPPED
            BackgroundStepResult(result.saved, bytes, failures, null, incomplete)
        }
    }
}

/**
 * What Swift posts after a run. [notify] is false when nothing new and nothing failed, so no
 * notification shows. [retry] asks for a backed-off retry ([BackgroundSyncPolicy.shouldRetry]).
 */
class BackgroundRunSummary(
    val notify: Boolean,
    val title: String,
    val body: String,
    val retry: Boolean,
    val newFiles: Int,
    val failures: Int,
)

/** A run in progress. [total] sensors were chosen. [cancel] stops it, the summary then never arrives. */
class BackgroundRunHandle internal constructor(val total: Int, private val job: Job?) {
    fun cancel() {
        job?.cancel()
    }
}

/**
 * Runs the shared runner over the Swift adapters. Swift passes these ports.
 * [browse] looks for `_aqsync._tcp` for up to the timeout and answers with service names and their
 * TXT stations. [sync] opens one sensor (over Wi-Fi when the service name is given, otherwise its
 * bonded peripheral), collects the missing files, closes it and answers. [isOwned] is true while the
 * user holds a link to that sensor.
 */
class BackgroundSyncBridge internal constructor(
    private val devices: DevicesBridge,
    private val settings: AutoSyncSettings,
    private val scope: CoroutineScope,
    private val browse: (timeoutMs: Long, done: (names: List<String>, stations: List<String>) -> Unit) -> Unit,
    private val sync: (id: String, wifiName: String?, done: (BackgroundStepResult) -> Unit) -> Unit,
    private val isOwned: (id: String) -> Boolean,
    private val log: (String) -> Unit,
) {
    private var running: Job? = null

    /** True while a run is going. A second request waits for the next schedule. */
    val busy: Boolean get() = running?.isActive == true

    /** The title the continued processing task shows, in the app language. A callback, so Swift needs no suspend call. */
    fun progressTitle(done: (String) -> Unit) {
        scope.launch { done(getString(Res.string.autosync_progress_title)) }
    }

    /** The scheduled run and a Bluetooth wake. Sensors with the switch on that are due. [only] limits it to those ids. */
    fun runDue(only: List<String>?, done: (BackgroundRunSummary) -> Unit): BackgroundRunHandle {
        val ids = only?.map { it.uppercase() }?.toSet()
        val chosen = BackgroundSyncPolicy.dueDevices(enabled(), settings.lastRuns(), nowMs())
            .filter { ids == null || it.address.uppercase() in ids }
        return start(chosen, done)
    }

    /**
     * A Bluetooth wake for [id]. The wake is recorded before the run, whatever comes of it, so the
     * sensor is not armed again within the period ([AutoSyncSettings.armable]).
     */
    fun runWake(id: String, done: (BackgroundRunSummary) -> Unit): BackgroundRunHandle {
        settings.markWake(id)
        return runDue(listOf(id), done)
    }

    private fun start(chosen: List<IosSyncDevice>, done: (BackgroundRunSummary) -> Unit): BackgroundRunHandle {
        if (busy || chosen.isEmpty()) {
            return BackgroundRunHandle(0, null).also {
                scope.launch { done(summary(BackgroundSyncReport(emptyList()))) }
            }
        }
        val runner = BackgroundSyncRunner(gateway, log = log)
        val job = scope.launch {
            val report = runner.run(devices = chosen)
            settings.markRun(IosAutoSync.finished(report))
            done(summary(report))
        }
        running = job
        return BackgroundRunHandle(chosen.size, job)
    }

    private fun nowMs(): Long = devices.nowMs()

    private fun all(): List<IosSyncDevice> = devices.knownNow().filter { IosKnown.isPeripheral(it.id) }.map {
        IosSyncDevice(it.id, it.name, it.station, it.hasWifiAccess)
    }

    private fun enabled(): List<IosSyncDevice> = all().filter { settings.isOn(it.address) }

    /** The summary copy, from the shared catalogue so Arabic follows the app language. No ids, no tokens. */
    internal suspend fun summary(report: BackgroundSyncReport<IosSyncDevice>): BackgroundRunSummary {
        val retry = BackgroundSyncPolicy.shouldRetry(report, 0)
        if (!report.shouldNotify) return BackgroundRunSummary(false, "", "", retry, report.newFiles, report.failures)
        val news = report.devicesWithNews
        val name = when (news.size) {
            1 -> news[0].result?.deviceName ?: news[0].device.name ?: getString(Res.string.notif_device)
            else -> getPluralString(Res.plurals.autosync_sensors, news.size, news.size)
        }
        val title = if (report.newFiles > 0) {
            getPluralString(Res.plurals.notif_synced, report.newFiles, report.newFiles, name, IosAutoSync.bytes(report.newBytes))
        } else {
            getString(Res.string.notif_problems, name)
        }
        val body = if (report.failures > 0) getPluralString(Res.plurals.notif_failures, report.failures, report.failures) else getString(Res.string.notif_verified)
        return BackgroundRunSummary(true, title, body, retry, report.newFiles, report.failures)
    }

    private val gateway = object : BackgroundSyncGateway<IosSyncDevice, IosLanTarget> {
        override suspend fun autoSyncDevices() = enabled()
        override suspend fun knownDevice(address: String) = all().firstOrNull { it.address.equals(address, ignoreCase = true) }
        override suspend fun allKnownDevices() = all()
        override fun interactiveSessionAddress(): String? = null
        override fun isDeviceOwned(address: String): Boolean = isOwned(address)

        override suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, IosLanTarget> = suspendCancellableCoroutine { cont ->
            browse(timeoutMs) { names, found ->
                if (cont.isActive) {
                    val map = names.indices.filter { it < found.size && found[it] in stations }.associate { found[it] to IosLanTarget(names[it], found[it]) }
                    cont.resume(map)
                }
            }
        }

        // A known record means INFO was read over an encrypted Bluetooth link, which the firmware
        // grants only to a bonded central. iOS has no bond query, so the connect itself is the check.
        override fun isBonded(address: String): Boolean = IosKnown.isPeripheral(address)

        override suspend fun sync(device: IosSyncDevice, link: BackgroundSyncLink<IosLanTarget>): BackgroundSyncResult {
            val wifi = (link as? BackgroundSyncLink.Lan)?.target?.serviceName
            val step = suspendCancellableCoroutine { cont ->
                sync(device.address, wifi) { r -> if (cont.isActive) cont.resume(r) }
            }
            val kind = if (wifi != null) TransportKind.LAN else TransportKind.BLE
            return BackgroundSyncResult(device.address, device.name, kind, step.newFiles, step.newBytes, step.failures, step.skipped, step.incomplete)
        }
    }
}
