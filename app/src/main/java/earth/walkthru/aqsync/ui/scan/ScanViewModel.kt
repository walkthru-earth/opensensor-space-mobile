package earth.walkthru.aqsync.ui.scan

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.DeviceSnapshot
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.BleScanner
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.ScannedDevice
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.Resolution
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.opensensor.devices.ActiveLink
import earth.walkthru.opensensor.devices.ArchiveGlance
import earth.walkthru.opensensor.devices.CollectAllPort
import earth.walkthru.opensensor.devices.DeviceRadio
import earth.walkthru.opensensor.devices.DevicesAction
import earth.walkthru.opensensor.devices.DevicesController
import earth.walkthru.opensensor.devices.DevicesUiState
import earth.walkthru.opensensor.devices.KnownDeviceStore
import earth.walkthru.opensensor.devices.KnownSensor
import earth.walkthru.opensensor.devices.LinkPhase
import earth.walkthru.opensensor.devices.NearbySensor
import earth.walkthru.opensensor.devices.ScanProblem
import earth.walkthru.opensensor.devices.WifiSensor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DeviceConnectionManager,
    private val knownDeviceDao: KnownDeviceDao,
    archivedFileDao: ArchivedFileDao,
    bucketDao: MetricBucketDao,
    private val autoSyncScheduler: AutoSyncScheduler,
    settings: AppSettings,
) : ViewModel() {

    private val summaries: Flow<Map<String, ArchiveGlance>> = combine(archivedFileDao.observeAll(), bucketDao.observeAllDays(Metrics.primary.id)) { files, days ->
        val latestDay = HashMap<String, earth.walkthru.aqsync.data.local.database.MetricBucket>()
        for (b in days) if (!latestDay.containsKey(b.station)) latestDay[b.station] = b // newest first
        files.groupBy { it.station }.mapValues { (station, list) ->
            val day = latestDay[station]
            ArchiveGlance(
                files = list.size,
                lastSyncedMs = list.maxOfOrNull { it.syncedAtEpochMs },
                lastDataUtcNs = list.mapNotNull { it.lastUtcNs }.maxOrNull(),
                lastDayStartS = day?.startEpochS,
                lastDayPm25Mean = day?.mean,
                lastDayCoverage = day?.let { (it.validRows.toFloat() / Resolution.DAY.expectedRows).coerceIn(0f, 1f) },
            )
        }
    }

    private val scanning = MutableStateFlow(false)
    private val scanProblem = MutableStateFlow<ScanProblem?>(null)
    private val seen = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())

    val detailLevel: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.SIMPLE)

    /** The debug fake entries, never in a release build. */
    val showFakeEntry: Boolean = BuildConfig.DEBUG

    /** Room behind the shared known-device port. The token ciphertext never leaves this class. */
    private val knownStore = object : KnownDeviceStore {
        override val devices: Flow<List<KnownSensor>> = knownDeviceDao.observeAll().map { rows ->
            rows.map { KnownSensor(it.address, it.name, it.station, it.lastSeenEpochMs, hasWifiAccess = it.hasLanToken) }
        }

        override suspend fun forget(id: String) = manager.controller(id).forget(id)
    }

    private val radio = object : DeviceRadio {
        override val nearby: Flow<List<NearbySensor>> = seen.map { m -> m.values.map { NearbySensor(it.address, it.name, it.rssi, it.bonded) } }
        override val scanning: Flow<Boolean> = this@ScanViewModel.scanning
        override val scanProblem: Flow<ScanProblem?> = this@ScanViewModel.scanProblem
        override val wifi: Flow<List<WifiSensor>> = manager.lanDevices.map { m -> m.values.map { WifiSensor(it.serviceName, it.station) } }
        override val active: Flow<List<ActiveLink>> = manager.snapshots.map { list -> list.filter { it.isActive() }.map { it.toActiveLink() } }
        override fun startScan() = this@ScanViewModel.startScan()
        override fun stopScan() = this@ScanViewModel.stopScan()
    }

    /** Android runs Collect all as one bounded background pass, sharing the two-transfer budget. */
    private val collect = object : CollectAllPort {
        override val running: Flow<Boolean> = autoSyncScheduler.observeActive()
        override fun collectAll() = autoSyncScheduler.collectAll()
    }

    private val controller = DevicesController(viewModelScope, knownStore, radio, summaries, collect)
    val state: StateFlow<DevicesUiState> get() = controller.state

    /** Shared actions go to the controller. Opening a sensor connects here when Bluetooth is allowed. */
    fun onAction(action: DevicesAction) {
        if (action is DevicesAction.Open) {
            if (action.connect) connect(action.id, action.name)
            return
        }
        controller.onAction(action)
    }

    private var scanJob: Job? = null
    private var pruneJob: Job? = null
    private var scanGeneration = 0

    fun startScan() {
        if (scanJob?.isActive == true) return
        val generation = ++scanGeneration
        scanProblem.value = null
        scanning.value = true
        pruneJob?.cancel()
        pruneJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                val cutoff = System.currentTimeMillis() - STALE_MS
                seen.update { m -> m.filterValues { it.lastSeenEpochMs >= cutoff } }
            }
        }
        scanJob = viewModelScope.launch {
            try {
                BleScanner.scan(context).takeWhile { ev ->
                    when (ev) {
                        is BleScanner.Event.Seen -> {
                            seen.update { it + (ev.device.address to ev.device) }
                            // Only an explicit connection enrolls a device; a passive scan must
                            // not recreate a row that the user chose to forget.
                            knownDeviceDao.touch(ev.device.address, ev.device.name, ev.device.lastSeenEpochMs)
                            true
                        }
                        is BleScanner.Event.Failed -> {
                            Log.w("ScanViewModel", "scan failed (${ev.errorCode}: ${ev.message})")
                            scanProblem.value = if (ev.errorCode == BleScanner.BLUETOOTH_OFF) ScanProblem.BLUETOOTH_OFF else ScanProblem.FAILED
                            false
                        }
                    }
                }.collect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ScanViewModel", "scan failed (${e::class.simpleName}: ${e.message})")
                scanProblem.value = ScanProblem.FAILED
            } finally {
                // A stopped run can finish after a new run starts. Only its own generation may
                // clear the current button state or cancel the current pruning loop.
                if (scanGeneration == generation) {
                    scanning.value = false
                    pruneJob?.cancel()
                    pruneJob = null
                    scanJob = null
                }
            }
        }
    }

    fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        pruneJob?.cancel()
        pruneJob = null
        scanning.value = false
    }

    fun connect(address: String, name: String?) {
        connectIfIdle(address, name, fake = false)
    }

    fun connectFake() {
        connectIfIdle(FakeTransport.FAKE_ADDRESS, "AQ-6b40 (fake)", fake = true)
    }

    /** Debug: the same fake, behaving like the LAN pipe (payload_max 1024, GET_TOKEN refused). */
    fun connectFakeLan() {
        connectIfIdle(FakeTransport.FAKE_LAN_ADDRESS, "AQ-6b40 (fake LAN)", fake = true)
    }

    /** Opening a device observes an existing owner; it never interrupts its connection or transfer. */
    private fun connectIfIdle(address: String, name: String?, fake: Boolean) {
        stopScan()
        val controller = manager.controller(address)
        val connection = controller.connectionState.value
        if (connection.isReady || connection.isBusy || controller.reconnecting.value != null || controller.backgroundOwner.value != null) return
        controller.connect(address, name, fake = fake)
    }

    override fun onCleared() {
        stopScan()
    }

    companion object {
        const val STALE_MS = 15_000L
    }
}

private fun DeviceSnapshot.isActive(): Boolean = connection != ConnectionState.Disconnected || backgroundOwner != null || reconnect != null

/** A live link as the shared list shows it. A failure reason stays here, the screen shows a phase. */
private fun DeviceSnapshot.toActiveLink(): ActiveLink = ActiveLink(
    id = address,
    name = name,
    phase = when {
        reconnect != null -> LinkPhase.RECONNECTING
        connection is ConnectionState.Ready -> LinkPhase.CONNECTED
        connection is ConnectionState.Bonding -> LinkPhase.PAIRING
        connection is ConnectionState.Failed -> LinkPhase.FAILED
        else -> LinkPhase.CONNECTING
    },
    collecting = progress.running,
    filesDone = progress.filesDone,
    filesTotal = progress.filesTotal,
    background = backgroundOwner != null,
)
