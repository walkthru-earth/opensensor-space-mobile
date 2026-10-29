package space.opensensor

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.DeviceContinuityPolicy
import space.opensensor.data.history.LatestDay
import space.opensensor.devices.ActiveLink
import space.opensensor.devices.ArchiveGlance
import space.opensensor.devices.CollectAllPlan
import space.opensensor.devices.CollectAllPort
import space.opensensor.devices.CollectAllQueue
import space.opensensor.devices.CollectRoute
import space.opensensor.devices.DeviceRadio
import space.opensensor.devices.DevicesController
import space.opensensor.devices.KnownDeviceStore
import space.opensensor.devices.KnownSensor
import space.opensensor.devices.LinkPhase
import space.opensensor.devices.NearbySensor
import space.opensensor.devices.ScanProblem
import space.opensensor.devices.WifiSensor

/**
 * One sensor this iPhone opened over Bluetooth, or holds a Wi-Fi credential for. [peripheral] is
 * the CoreBluetooth identifier, [device] the 12-hex hardware id from INFO. Never a token.
 */
internal data class KnownRecord(
    val peripheral: String,
    val name: String?,
    val thing: String,
    val device: String,
    val lastSeenMs: Long?,
    val olderThings: List<String> = emptyList(),
)

/** Narrow result for the native BLE adapter. No secret material crosses this boundary. */
class SensorOpenResult(val accepted: Boolean, val notice: CompanionNotice?, val credentialToRemove: String?)

/** Where the bridge keeps its known records. UserDefaults on the phone, memory in tests. */
internal interface KnownRecordStorage {
    fun load(): String?
    fun save(text: String)
}

/** The pure rules behind the iOS known list, so the tests read them without CoreBluetooth. */
internal object IosKnown {
    /** Ids of links the bridge reports for an open Wi-Fi session, never a peripheral id. */
    const val WIFI_PREFIX = "wifi:"

    /**
     * Records and Keychain credentials, one sensor per peripheral. A record names the sensor and
     * when it was last opened. A credential alone still lists it. [KnownSensor.hasWifiAccess] says
     * a credential is held for its hardware id.
     */
    fun merge(records: List<KnownRecord>, credentials: List<KnownRecord>): List<KnownSensor> {
        val access = credentials.map { it.device.lowercase() to it.thing }.toSet()
        val byPeripheral = LinkedHashMap<String, KnownRecord>()
        for (c in credentials) byPeripheral[c.peripheral.uppercase()] = c
        for (r in records) byPeripheral[r.peripheral.uppercase()] = r
        return byPeripheral.map { (id, r) -> KnownSensor(id, r.name, r.thing, r.lastSeenMs, (r.device.lowercase() to r.thing) in access) }
    }

    /** Adds or refreshes [record], newest first. */
    fun remember(records: List<KnownRecord>, record: KnownRecord): List<KnownRecord> =
        listOf(record) + records.filterNot { it.peripheral.equals(record.peripheral, ignoreCase = true) }

    fun forget(records: List<KnownRecord>, peripheral: String): List<KnownRecord> =
        records.filterNot { it.peripheral.equals(peripheral, ignoreCase = true) }

    /** Files and the newest sync per thing, as Android reads its Room index. [withDays] adds the day. */
    fun glance(records: List<ArchiveRecord>): Map<String, ArchiveGlance> =
        records.groupBy { it.thing }.mapValues { (_, list) ->
            ArchiveGlance(
                files = list.size,
                lastSyncedMs = list.maxOfOrNull { it.syncedAtEpochMs },
                lastDataUtcNs = list.mapNotNull { it.lastUtcNs }.maxOrNull(),
            )
        }

    /**
     * [glances] with each thing's latest day from [latestDay], the line Android fills from Room. The
     * files show at once and each day joins once its tiers answer.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun withDays(glances: Flow<Map<String, ArchiveGlance>>, latestDay: (String) -> Flow<LatestDay?>): Flow<Map<String, ArchiveGlance>> {
        val days = glances.map { it.keys }.distinctUntilChanged().flatMapLatest { things ->
            if (things.isEmpty()) flowOf(emptyMap()) else combine(things.map { t -> latestDay(t).map { t to it } }) { it.toMap() }
        }.onStart { emit(emptyMap()) }
        return combine(glances, days) { g, d -> g.mapValues { (thing, glance) -> glance.copy(lastDay = d[thing]) } }
    }

    /** One record per line, tab separated. The sixth field records older thing epochs. */
    fun encode(records: List<KnownRecord>): String = records.joinToString("\n") { r ->
        listOf(r.peripheral, r.name?.replace(Regex("[\\t\\r\\n]"), " ").orEmpty(), r.thing, r.device, r.lastSeenMs?.toString().orEmpty(), r.olderThings.joinToString(","))
            .joinToString("\t")
    }

    /** Lines that do not parse are dropped, so a damaged entry costs one sensor, not the list. */
    fun decode(text: String?): List<KnownRecord> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size !in 5..6 || f[0].isBlank() || !SyncWire.isThingId(f[2]) || !Regex("[0-9a-fA-F]{12}").matches(f[3])) return@mapNotNull null
        val older = f.getOrNull(5).orEmpty().split(',').filter { SyncWire.isThingId(it) && it != f[2] }.distinct()
        KnownRecord(f[0], f[1].ifBlank { null }, f[2], f[3], f[4].toLongOrNull(), older)
    }.distinctBy { it.peripheral.uppercase() }.toList()

    /**
     * The known ids Collect all must skip because a link the user holds already reaches them. A
     * Bluetooth link names its peripheral id. A Wi-Fi link names its service name, which maps to a
     * known sensor through the TXT thing in [lanThings]. Ids compare without case.
     */
    fun busyIds(active: List<ActiveLink>, lanThings: Map<String, String>, known: List<KnownSensor>): Set<String> {
        val busy = HashSet<String>()
        for (link in active) {
            busy += link.id
            if (link.id.startsWith(WIFI_PREFIX)) {
                val thing = lanThings[link.id.removePrefix(WIFI_PREFIX)] ?: continue
                known.filter { it.thing == thing }.forEach { busy += it.id }
            } else {
                known.filter { it.id.equals(link.id, ignoreCase = true) }.forEach { busy += it.id }
            }
        }
        return busy
    }

    /**
     * The open links. The one Bluetooth link by its peripheral id, then each Wi-Fi session that is
     * connecting, connected or paused, by service name. A Bluetooth link that dropped under a run
     * shows [LinkPhase.RECONNECTING] until it is back, and a lost bond shows [LinkPhase.FAILED]
     * until a new connection starts. Core Bluetooth reports no bonding step, so iOS never shows
     * [LinkPhase.PAIRING]. [bluetoothBackground] and [lanBackground] mark Auto-sync steps.
     */
    fun active(
        selectedId: String?,
        connected: Boolean,
        syncBusy: Boolean,
        syncDone: Int,
        syncTotal: Int,
        name: String?,
        lanSessions: Map<String, LanSessionState>,
        reconnectingId: String? = null,
        failedId: String? = null,
        bluetoothBackground: Boolean = false,
        lanPaused: Set<String> = emptySet(),
        lanBackground: Set<String> = emptySet(),
    ): List<ActiveLink> {
        val bluetooth = (selectedId ?: reconnectingId ?: failedId)?.let {
            val phase = when {
                connected -> LinkPhase.CONNECTED
                reconnectingId != null && it == reconnectingId -> LinkPhase.RECONNECTING
                selectedId == null && it == failedId -> LinkPhase.FAILED
                else -> LinkPhase.CONNECTING
            }
            ActiveLink(
                id = it,
                name = name,
                phase = phase,
                collecting = syncBusy && (connected || phase == LinkPhase.RECONNECTING),
                filesDone = syncDone,
                filesTotal = syncTotal,
                background = bluetoothBackground,
            )
        }
        val wifi = lanSessions.entries.sortedBy { it.key }
            .filter { it.value.connected || it.value.busy || it.key in lanPaused }
            .map { (n, s) ->
                val paused = n in lanPaused && !s.connected
                ActiveLink(
                    id = WIFI_PREFIX + n,
                    name = n,
                    phase = when {
                        s.connected -> LinkPhase.CONNECTED
                        paused -> LinkPhase.RECONNECTING
                        else -> LinkPhase.CONNECTING
                    },
                    collecting = s.busy && (s.connected || paused),
                    filesDone = s.filesDone,
                    filesTotal = s.filesTotal,
                    background = n in lanBackground,
                )
            }
        return listOfNotNull(bluetooth) + wifi
    }

    /** True for a CoreBluetooth identifier, the only id the Bluetooth adapter can open. */
    fun isPeripheral(id: String): Boolean =
        Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}").matches(id)
}

/**
 * The iOS side of My devices. It turns the Swift adapters' state ([CompanionState]), the saved
 * known records, the Keychain credential list and the Room index into the three ports of
 * `:feature:devices`. Swift supplies plain callbacks and reports back. Only ids, names, things
 * and hardware ids cross, never a PIN or token. Call everything on the main thread.
 *
 * Collect all runs one sensor at a time, because the Bluetooth adapter holds one link. [collect]
 * opens the sensor (over Wi-Fi when [collect]'s name is given), syncs it and closes it, then Swift
 * calls [collectFinished].
 */
class DevicesBridge internal constructor(
    internal val state: CompanionState,
    private val archive: Flow<List<ArchiveRecord>>,
    private val storage: KnownRecordStorage,
    private val now: () -> Long,
    /** The scan My devices starts on its own. Bluetooth only until the user first tapped Scan. */
    private val startScan: () -> Unit,
    /** The user tapped Scan or Find, which also browses the local network and may ask for access. */
    private val tapScan: () -> Unit,
    private val stopScan: () -> Unit,
    private val forget: (peripheral: String, device: String?) -> Unit,
    private val collect: (id: String, wifiName: String?) -> Unit,
    private val latestDay: (thing: String) -> Flow<LatestDay?> = { flowOf(null) },
    /** Drops the saved Discover cells of a forgotten sensor's things, current and older. */
    private val forgetPlaces: suspend (things: List<String>) -> Unit = {},
    /** Outlives every screen, as Android's app scope does, so leaving Today never cancels a Forget. */
    private val forgetScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val records = MutableStateFlow(IosKnown.decode(storage.load()))
    private val credentials = MutableStateFlow<List<KnownRecord>>(emptyList())
    private val collecting = MutableStateFlow(false)
    private var queue: CollectAllQueue? = null

    /** The pass going now was cancelled, so a failed step takes no fallback attempt. */
    private var collectCancelled = false

    internal val known: StateFlow<List<KnownRecord>> get() = records.asStateFlow()

    /** The setup wizard's Find is a tap, so it runs the same scan as tapping Scan on My devices. */
    internal fun startFind() = tapScan()

    /** The user tapped Scan on My devices. */
    internal fun scanTapped() = tapScan()

    internal fun stopFind() = stopScan()

    /**
     * Hears each Collect all change on the main thread, steps ended and steps planned, then (0, 0)
     * once the pass ended. iOS 26 shows a running pass as a continued task (#58).
     */
    var collectAllChanged: ((done: Int, total: Int) -> Unit)? = null

    /** True while a Collect all pass owns the Bluetooth link between its steps. */
    internal val collectAllRunning: Boolean get() = collecting.value

    /** The hardware ids, lowercase, that hold Wi-Fi access in the Keychain. Never the token. */
    internal val wifiAccessDevices: Flow<Set<String>> get() = credentials.map { list -> list.mapTo(HashSet()) { it.device } }

    /**
     * Validate BLE INFO before a link may expose readings, credentials or archive operations.
     * A new thing on the same hardware starts a new archive epoch; old files keep their thing.
     */
    fun sensorOpened(peripheral: String, name: String?, thing: String, device: String): SensorOpenResult {
        val previous = records.value.firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }
        val credential = credentials.value.firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }
        val expected = previous ?: credential
        val decision = DeviceContinuityPolicy.evaluate(
            expected?.let { DeviceContinuityPolicy.Identity(it.peripheral, it.device, it.thing) },
            DeviceContinuityPolicy.Identity(peripheral, device, thing),
            authenticatedBle = true,
        )
        if (decision is DeviceContinuityPolicy.Decision.Reject) {
            val notice = when (decision.reason) {
                DeviceContinuityPolicy.Reason.HARDWARE_CHANGED, DeviceContinuityPolicy.Reason.HARDWARE_MISSING -> CompanionNotice.HARDWARE_CHANGED
                else -> CompanionNotice.INFO_INVALID
            }
            return SensorOpenResult(false, notice, null)
        }
        decision as DeviceContinuityPolicy.Decision.Accept
        val staleCredential = credential?.takeIf { it.device != device.lowercase() || it.thing != thing || decision.invalidateLanToken }
        val older = (listOfNotNull(expected?.thing?.takeIf { it != thing }) + previous?.olderThings.orEmpty())
            .filter { it != thing }.distinct()
        save(IosKnown.remember(records.value, KnownRecord(peripheral, name?.takeIf { it.isNotBlank() } ?: previous?.name, thing, device.lowercase(), now(), older)))
        return SensorOpenResult(true, null, staleCredential?.device)
    }

    /** The Keychain credentials, as three lists of the same length. Never the token. */
    fun credentialsChanged(peripherals: List<String>, things: List<String>, devices: List<String>) {
        credentials.value = peripherals.indices.filter { it < things.size && it < devices.size }.map {
            KnownRecord(peripherals[it], null, things[it], devices[it].lowercase(), null)
        }
    }

    /** A Collect all step ended. [ok] is true when every listed file is on this phone. */
    fun collectFinished(id: String, ok: Boolean) {
        val q = queue ?: return
        q.finished(id, ok).forEach { collect(it.id, it.wifiName) }
        settle(q)
    }

    /** Whether a failed Wi-Fi step of the pass going now may try again over Bluetooth. */
    fun collectRetries(id: String): Boolean {
        val q = queue ?: return false
        return !collectCancelled && id in q.runningIds
    }

    /** Leaving the foreground ends Collect all. A running step finishes or fails on its own. */
    fun cancelCollectAll() {
        val q = queue ?: return
        collectCancelled = true
        q.cancel()
        settle(q)
    }

    /**
     * One rule for every user path on a sensor id. The user wins, so Collect all stops first when the
     * action targets a sensor the pass holds now or still plans. Its running step finishes on its
     * own. A [takesBluetooth] action, opening a sensor, also stops a pass whose running step is on
     * Bluetooth, because the phone holds one Bluetooth link. Any other action leaves the pass going.
     */
    fun userFirst(action: (String) -> Unit, takesBluetooth: Boolean = false): (String) -> Unit = { id ->
        stopCollectAllFor(id, takesBluetooth)
        action(id)
    }

    fun <B> userFirst(action: (String, B) -> Unit): (String, B) -> Unit = { id, b ->
        stopCollectAllFor(id, takesBluetooth = false)
        action(id, b)
    }

    private fun stopCollectAllFor(id: String, takesBluetooth: Boolean) {
        val q = queue ?: return
        val targeted = (q.runningIds + q.plannedIds).any { it.equals(id, ignoreCase = true) }
        val onBluetooth = takesBluetooth && q.runningSteps.any { it.route == CollectRoute.BLUETOOTH }
        if (targeted || onBluetooth) cancelCollectAll()
    }

    /** The name the device page shows for a row id, from the scan, the known records or the Wi-Fi name. */
    internal fun nameFor(id: String): String? =
        if (id.startsWith(IosKnown.WIFI_PREFIX)) {
            id.removePrefix(IosKnown.WIFI_PREFIX)
        } else {
            state.devices.firstOrNull { it.id == id }?.name
                ?: records.value.firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.name
        }

    internal fun nowMs(): Long = now()

    /** The 12-hex hardware id INFO gave for this peripheral, so Swift can find its Wi-Fi session. */
    fun deviceFor(peripheral: String): String? =
        (records.value + credentials.value).firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }?.device

    /** The saved peripheral id for a 12-hex hardware id, so a retry can reopen its Bluetooth link. */
    fun peripheralFor(device: String): String? =
        (records.value + credentials.value).firstOrNull { it.device.equals(device, ignoreCase = true) }?.peripheral

    /** The verified thing for a previously opened peripheral. This is only a discovery hint. */
    fun thingFor(peripheral: String): String? =
        (records.value + credentials.value).firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }?.thing

    /** Current and earlier authenticated BLE thing epochs for this saved sensor's archive. */
    fun thingsFor(peripheral: String): List<String> {
        val record = records.value.firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }
        return if (record != null) listOf(record.thing) + record.olderThings else listOfNotNull(thingFor(peripheral))
    }

    /** Resolve an active Wi-Fi service back to its enrolled sensor's navigation id. */
    fun peripheralForLan(name: String): String? {
        state.lanInfo[name]?.dev?.let { verified ->
            return (credentials.value + records.value).firstOrNull {
                it.device.equals(verified, ignoreCase = true)
            }?.peripheral
        }
        val thing = state.lanInfo[name]?.thing ?: state.lanThings[name] ?: return null
        return (credentials.value + records.value).firstOrNull { it.thing == thing }?.peripheral
    }

    private val openRequests = MutableStateFlow(false)

    /** True after a tapped Auto-sync summary asked for My devices, until the shell shows it. */
    internal val openRequested: StateFlow<Boolean> get() = openRequests.asStateFlow()

    /** The user tapped an Auto-sync summary. The shell opens My devices once it is on screen. */
    fun openMyDevices() {
        openRequests.value = true
    }

    internal fun openHandled() {
        openRequests.value = false
    }

    internal fun knownNow(): List<KnownSensor> = IosKnown.merge(records.value, credentials.value)

    internal fun wifiNow(): List<WifiSensor> = state.lanDevices.map { WifiSensor(it, state.lanThings[it]) }

    internal fun activeNow(): List<ActiveLink> {
        val name = (state.selectedId ?: state.reconnectingId ?: state.failedId)?.let { id ->
            state.devices.firstOrNull { it.id == id }?.name ?: records.value.firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.name
        }
        val progress = state.syncProgress
        return IosKnown.active(
            state.selectedId, state.connected, state.syncBusy, progress?.filesDone ?: 0, progress?.filesTotal ?: 0, name, state.lanSessions,
            reconnectingId = state.reconnectingId,
            failedId = state.failedId,
            bluetoothBackground = state.bluetoothBackground,
            lanPaused = state.lanPaused,
            lanBackground = state.lanBackground,
        )
    }

    internal fun startCollectAll() {
        if (queue?.active == true) return
        val known = knownNow()
        val plan = CollectAllPlan.plan(known, wifiNow(), IosKnown.busyIds(activeNow(), state.lanThings, known))
        if (plan.isEmpty()) return
        val q = CollectAllQueue(plan)
        queue = q
        collectCancelled = false
        collecting.value = true
        q.start().forEach { collect(it.id, it.wifiName) }
        settle(q)
    }

    private fun settle(q: CollectAllQueue) {
        if (queue !== q) return
        if (q.active) {
            collectAllChanged?.invoke(q.succeeded + q.failed, q.total)
        } else {
            queue = null
            collecting.value = false
            collectAllChanged?.invoke(0, 0)
        }
    }

    /**
     * A confirmed Forget, run in [forgetScope]. A removal that fails keeps the record, so the sensor
     * stays listed and Forget can run again. The log names the error class only.
     */
    internal fun forgetLater(id: String): Job = forgetScope.launch {
        try {
            forgetNow(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("forget kept the record: ${e::class.simpleName}")
        }
    }

    /**
     * The cells go before the record. A record kept while they are removed lets a Forget that
     * stopped halfway run again, so no cell outlives its sensor.
     */
    internal suspend fun forgetNow(id: String) {
        val device = (records.value + credentials.value).firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.device
        val credentialThings = credentials.value.filter { it.peripheral.equals(id, ignoreCase = true) }.map { it.thing }
        forgetPlaces((thingsFor(id) + credentialThings).distinct())
        save(IosKnown.forget(records.value, id))
        credentials.value = IosKnown.forget(credentials.value, id)
        forget(id, device)
    }

    private fun save(list: List<KnownRecord>) {
        records.value = list
        storage.save(IosKnown.encode(list))
    }

    private val store = object : KnownDeviceStore {
        override val devices: Flow<List<KnownSensor>> = combine(records, credentials) { r, c -> IosKnown.merge(r, c) }
    }

    private val radio = object : DeviceRadio {
        override val nearby = snapshotFlow { state.devices.map { NearbySensor(it.id, it.name, it.rssi) } }
        override val scanning = snapshotFlow { state.scanning || state.lanScanning }
        override val scanProblem = snapshotFlow {
            when {
                state.lanAccessDenied -> ScanProblem.LOCAL_NETWORK_DENIED
                state.bluetoothPoweredOff -> ScanProblem.BLUETOOTH_OFF
                else -> null
            }
        }
        override val wifi = snapshotFlow { wifiNow() }

        // Reads the known records too, so a renamed link follows them.
        override val active = combine(snapshotFlow { activeNow() }, records) { _, _ -> activeNow() }
        override fun startScan() = this@DevicesBridge.startScan()
        override fun stopScan() = this@DevicesBridge.stopScan()
    }

    private val collectPort = object : CollectAllPort {
        override val running: Flow<Boolean> = collecting
        override fun collectAll() = startCollectAll()
    }

    /** A controller for one My devices entry, cleared with its scope. */
    internal fun controller(scope: CoroutineScope): DevicesController =
        DevicesController(scope, store, radio, IosKnown.withDays(archive.map(IosKnown::glance), latestDay), collectPort)
}
