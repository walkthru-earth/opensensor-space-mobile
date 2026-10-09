package earth.walkthru.opensensor

import androidx.compose.runtime.snapshotFlow
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.opensensor.devices.ActiveLink
import earth.walkthru.opensensor.devices.ArchiveGlance
import earth.walkthru.opensensor.devices.CollectAllPlan
import earth.walkthru.opensensor.devices.CollectAllPort
import earth.walkthru.opensensor.devices.CollectAllQueue
import earth.walkthru.opensensor.devices.DeviceRadio
import earth.walkthru.opensensor.devices.DevicesController
import earth.walkthru.opensensor.devices.KnownDeviceStore
import earth.walkthru.opensensor.devices.KnownSensor
import earth.walkthru.opensensor.devices.LinkPhase
import earth.walkthru.opensensor.devices.NearbySensor
import earth.walkthru.opensensor.devices.ScanProblem
import earth.walkthru.opensensor.devices.WifiSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * One sensor this iPhone opened over Bluetooth, or holds a Wi-Fi credential for. [peripheral] is
 * the CoreBluetooth identifier, [device] the 12-hex hardware id from INFO. Never a token.
 */
internal data class KnownRecord(
    val peripheral: String,
    val name: String?,
    val station: String,
    val device: String,
    val lastSeenMs: Long?,
)

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
        val access = credentials.map { it.device.lowercase() }.toSet()
        val byPeripheral = LinkedHashMap<String, KnownRecord>()
        for (c in credentials) byPeripheral[c.peripheral.uppercase()] = c
        for (r in records) byPeripheral[r.peripheral.uppercase()] = r
        return byPeripheral.map { (id, r) -> KnownSensor(id, r.name, r.station, r.lastSeenMs, r.device.lowercase() in access) }
    }

    /** Adds or refreshes [record], newest first. */
    fun remember(records: List<KnownRecord>, record: KnownRecord): List<KnownRecord> =
        listOf(record) + records.filterNot { it.peripheral.equals(record.peripheral, ignoreCase = true) }

    fun forget(records: List<KnownRecord>, peripheral: String): List<KnownRecord> =
        records.filterNot { it.peripheral.equals(peripheral, ignoreCase = true) }

    /** Files and the newest sync per station, as Android reads its Room index. iOS has no day mean yet. */
    fun glance(records: List<ArchiveRecord>): Map<String, ArchiveGlance> =
        records.groupBy { it.station }.mapValues { (_, list) ->
            ArchiveGlance(
                files = list.size,
                lastSyncedMs = list.maxOfOrNull { it.syncedAtEpochMs },
                lastDataUtcNs = list.mapNotNull { it.lastUtcNs }.maxOrNull(),
            )
        }

    /** One record per line, tab separated. A name loses tabs and line breaks. */
    fun encode(records: List<KnownRecord>): String = records.joinToString("\n") { r ->
        listOf(r.peripheral, r.name?.replace(Regex("[\\t\\r\\n]"), " ").orEmpty(), r.station, r.device, r.lastSeenMs?.toString().orEmpty())
            .joinToString("\t")
    }

    /** Lines that do not parse are dropped, so a damaged entry costs one sensor, not the list. */
    fun decode(text: String?): List<KnownRecord> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 5 || f[0].isBlank() || f[2].isBlank() || f[3].isBlank()) return@mapNotNull null
        KnownRecord(f[0], f[1].ifBlank { null }, f[2], f[3], f[4].toLongOrNull())
    }.distinctBy { it.peripheral.uppercase() }.toList()

    /**
     * The known ids Collect all must skip because a link the user holds already reaches them. A
     * Bluetooth link names its peripheral id. A Wi-Fi link names its service name, which maps to a
     * known sensor through the TXT station in [lanStations]. Ids compare without case.
     */
    fun busyIds(active: List<ActiveLink>, lanStations: Map<String, String>, known: List<KnownSensor>): Set<String> {
        val busy = HashSet<String>()
        for (link in active) {
            busy += link.id
            if (link.id.startsWith(WIFI_PREFIX)) {
                val station = lanStations[link.id.removePrefix(WIFI_PREFIX)] ?: continue
                known.filter { it.station == station }.forEach { busy += it.id }
            } else {
                known.filter { it.id.equals(link.id, ignoreCase = true) }.forEach { busy += it.id }
            }
        }
        return busy
    }

    /**
     * The open links. The one Bluetooth link by its peripheral id, then each Wi-Fi session that is
     * connecting or connected, by service name.
     */
    fun active(
        selectedId: String?,
        connected: Boolean,
        syncBusy: Boolean,
        syncDone: Int,
        syncTotal: Int,
        name: String?,
        lanSessions: Map<String, LanSessionState>,
    ): List<ActiveLink> {
        val bluetooth = selectedId?.let {
            ActiveLink(
                id = it,
                name = name,
                phase = if (connected) LinkPhase.CONNECTED else LinkPhase.CONNECTING,
                collecting = connected && syncBusy,
                filesDone = syncDone,
                filesTotal = syncTotal,
            )
        }
        val wifi = lanSessions.entries.sortedBy { it.key }.filter { it.value.connected || it.value.busy }.map { (n, s) ->
            ActiveLink(
                id = WIFI_PREFIX + n,
                name = n,
                phase = if (s.connected) LinkPhase.CONNECTED else LinkPhase.CONNECTING,
                collecting = s.connected && s.busy,
                filesDone = s.filesDone,
                filesTotal = s.filesTotal,
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
 * known records, the Keychain credential list and the receipt index into the three ports of
 * `:feature:devices`. Swift supplies plain callbacks and reports back. Only ids, names, stations
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
    private val startScan: () -> Unit,
    private val stopScan: () -> Unit,
    private val forget: (peripheral: String, device: String?) -> Unit,
    private val collect: (id: String, wifiName: String?) -> Unit,
) {
    private val records = MutableStateFlow(IosKnown.decode(storage.load()))
    private val credentials = MutableStateFlow<List<KnownRecord>>(emptyList())
    private val collecting = MutableStateFlow(false)
    private var queue: CollectAllQueue? = null

    internal val known: StateFlow<List<KnownRecord>> get() = records.asStateFlow()

    /** True while a Collect all pass owns the Bluetooth link between its steps. */
    internal val collectAllRunning: Boolean get() = collecting.value

    /** The hardware ids, lowercase, that hold Wi-Fi access in the Keychain. Never the token. */
    internal val wifiAccessDevices: Flow<Set<String>> get() = credentials.map { list -> list.mapTo(HashSet()) { it.device } }

    /** Bluetooth read INFO from a sensor the user opened. It is known from now on. */
    fun sensorOpened(peripheral: String, name: String?, station: String, device: String) {
        save(IosKnown.remember(records.value, KnownRecord(peripheral, name?.takeIf { it.isNotBlank() }, station, device.lowercase(), now())))
    }

    /** The Keychain credentials, as three lists of the same length. Never the token. */
    fun credentialsChanged(peripherals: List<String>, stations: List<String>, devices: List<String>) {
        credentials.value = peripherals.indices.filter { it < stations.size && it < devices.size }.map {
            KnownRecord(peripherals[it], null, stations[it], devices[it].lowercase(), null)
        }
    }

    /** A Collect all step ended. [ok] is true when every listed file is on this phone. */
    fun collectFinished(id: String, ok: Boolean) {
        val q = queue ?: return
        q.finished(id, ok).forEach { collect(it.id, it.wifiName) }
        settle(q)
    }

    /** Leaving the foreground ends Collect all. A running step finishes or fails on its own. */
    fun cancelCollectAll() {
        val q = queue ?: return
        q.cancel()
        settle(q)
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
        (credentials.value + records.value).firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }?.device

    /** The verified station for a previously opened peripheral. This is only a discovery hint. */
    fun stationFor(peripheral: String): String? =
        (credentials.value + records.value).firstOrNull { it.peripheral.equals(peripheral, ignoreCase = true) }?.station

    /** Resolve an active Wi-Fi service back to its enrolled sensor's navigation id. */
    fun peripheralForLan(name: String): String? {
        state.lanInfo[name]?.dev?.let { verified ->
            return (credentials.value + records.value).firstOrNull {
                it.device.equals(verified, ignoreCase = true)
            }?.peripheral
        }
        val station = state.lanInfo[name]?.station ?: state.lanStations[name] ?: return null
        return (credentials.value + records.value).firstOrNull { it.station == station }?.peripheral
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

    internal fun wifiNow(): List<WifiSensor> = state.lanDevices.map { WifiSensor(it, state.lanStations[it]) }

    internal fun activeNow(): List<ActiveLink> {
        val name = state.selectedId?.let { id ->
            state.devices.firstOrNull { it.id == id }?.name ?: records.value.firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.name
        }
        val progress = state.syncProgress
        return IosKnown.active(state.selectedId, state.connected, state.syncBusy, progress?.filesDone ?: 0, progress?.filesTotal ?: 0, name, state.lanSessions)
    }

    internal fun startCollectAll() {
        if (queue?.active == true) return
        val known = knownNow()
        val plan = CollectAllPlan.plan(known, wifiNow(), IosKnown.busyIds(activeNow(), state.lanStations, known))
        if (plan.isEmpty()) return
        val q = CollectAllQueue(plan)
        queue = q
        collecting.value = true
        q.start().forEach { collect(it.id, it.wifiName) }
        settle(q)
    }

    private fun settle(q: CollectAllQueue) {
        if (queue === q && !q.active) {
            queue = null
            collecting.value = false
        }
    }

    internal fun forgetNow(id: String) {
        val device = (records.value + credentials.value).firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.device
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
        override suspend fun forget(id: String) = forgetNow(id)
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
        DevicesController(scope, store, radio, archive.map(IosKnown::glance), collectPort)
}
