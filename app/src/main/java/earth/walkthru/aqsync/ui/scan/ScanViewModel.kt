package earth.walkthru.aqsync.ui.scan

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.R
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
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Home knows about a logger from the phone archive alone, no link needed. */
data class LoggerSummary(
    val files: Int,
    val lastSyncedEpochMs: Long?,
    /** Latest timed row on this phone (UTC ns), if any file has a clock. */
    val lastDataUtcNs: Long?,
    /** The most recent UTC day with valid readings: its start and PM2.5 average. */
    val lastDayStartS: Long?,
    val lastDayPm25Mean: Float?,
    val lastDayCoverage: Float?,
)

data class ScanUiState(
    val scanning: Boolean = false,
    val activeDevices: List<DeviceSnapshot> = emptyList(),
    val scanError: String? = null,
    /** Advertisers seen in the last ~15 s, strongest first. */
    val nearby: List<ScannedDevice> = emptyList(),
    val known: List<KnownDevice> = emptyList(),
    val showFakeEntry: Boolean = BuildConfig.DEBUG,
    /** Stations currently advertised on the LAN, for the "on LAN" tag. */
    val lanStations: Set<String> = emptySet(),
    /** Keyed by station. */
    val summaries: Map<String, LoggerSummary> = emptyMap(),
    /** A manual or periodic background run is queued or running. */
    val backgroundSyncActive: Boolean = false,
    /** Simple hides addresses, signal numbers and station IDs; Developer shows them. */
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
)

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

    private val summaries: Flow<Map<String, LoggerSummary>> = combine(archivedFileDao.observeAll(), bucketDao.observeAllDays(Metrics.primary.id)) { files, days ->
        val latestDay = HashMap<String, earth.walkthru.aqsync.data.local.database.MetricBucket>()
        for (b in days) if (!latestDay.containsKey(b.station)) latestDay[b.station] = b // newest first
        files.groupBy { it.station }.mapValues { (station, list) ->
            val day = latestDay[station]
            LoggerSummary(
                files = list.size,
                lastSyncedEpochMs = list.maxOfOrNull { it.syncedAtEpochMs },
                lastDataUtcNs = list.mapNotNull { it.lastUtcNs }.maxOrNull(),
                lastDayStartS = day?.startEpochS,
                lastDayPm25Mean = day?.mean,
                lastDayCoverage = day?.let { (it.validRows.toFloat() / Resolution.DAY.expectedRows).coerceIn(0f, 1f) },
            )
        }
    }

    private val scanning = MutableStateFlow(false)
    private val scanError = MutableStateFlow<String?>(null)
    private val seen = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    val uiState: StateFlow<ScanUiState> = combine(
        scanning,
        scanError,
        seen,
        knownDeviceDao.observeAll(),
        manager.lanDevices,
        summaries,
        autoSyncScheduler.observeActive(),
        manager.snapshots,
        settings.detailLevel,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val nearby = (values[2] as Map<String, ScannedDevice>).values
            .sortedByDescending { it.rssi }
        @Suppress("UNCHECKED_CAST")
        ScanUiState(
            scanning = values[0] as Boolean,
            activeDevices = (values[7] as List<*>).filterIsInstance<DeviceSnapshot>().filter { it.connection != ConnectionState.Disconnected || it.backgroundOwner != null || it.reconnect != null },
            scanError = values[1] as String?,
            nearby = nearby,
            known = values[3] as List<KnownDevice>,
            lanStations = (values[4] as Map<String, *>).keys,
            summaries = values[5] as Map<String, LoggerSummary>,
            backgroundSyncActive = values[6] as Boolean,
            detailLevel = values[8] as DetailLevel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    /** One background pass over known devices, sharing the app's two-transfer budget. */
    fun collectAll() = autoSyncScheduler.collectAll()

    private var scanJob: Job? = null
    private var pruneJob: Job? = null
    private var scanGeneration = 0

    fun startScan() {
        if (scanJob?.isActive == true) return
        val generation = ++scanGeneration
        scanError.value = null
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
                            scanError.value = context.getString(if (ev.errorCode == BleScanner.BLUETOOTH_OFF) R.string.scan_bluetooth_off else R.string.scan_failed)
                            false
                        }
                    }
                }.collect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ScanViewModel", "scan failed (${e::class.simpleName}: ${e.message})")
                scanError.value = context.getString(R.string.scan_failed)
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

    fun forget(address: String) {
        viewModelScope.launch { manager.controller(address).forget(address) }
    }

    override fun onCleared() {
        stopScan()
    }

    companion object {
        const val STALE_MS = 15_000L
    }
}
