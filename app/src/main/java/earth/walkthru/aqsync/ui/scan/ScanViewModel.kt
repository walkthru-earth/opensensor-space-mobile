package earth.walkthru.aqsync.ui.scan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.BleScanner
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.ScannedDevice
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.Resolution
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Home knows about a logger from the phone archive alone — no link needed. */
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
    val scanError: String? = null,
    /** Advertisers seen in the last ~15 s, strongest first. */
    val nearby: List<ScannedDevice> = emptyList(),
    val known: List<KnownDevice> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Address we are currently trying to connect to, for the row spinner. */
    val connectingTo: String? = null,
    val showFakeEntry: Boolean = BuildConfig.DEBUG,
    /** Stations currently advertised on the LAN, for the "on LAN" tag. */
    val lanStations: Set<String> = emptySet(),
    /** Keyed by station. */
    val summaries: Map<String, LoggerSummary> = emptyMap(),
    /** A manual or periodic background run is queued or running. */
    val backgroundSyncActive: Boolean = false,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceConnectionRepository,
    archivedFileDao: ArchivedFileDao,
    bucketDao: MetricBucketDao,
    private val autoSyncScheduler: AutoSyncScheduler,
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
    private val connectingTo = MutableStateFlow<String?>(null)

    val events: SharedFlow<DeviceEvent> = repository.events

    val uiState: StateFlow<ScanUiState> = combine(
        scanning, scanError, seen, repository.knownDevices, repository.connectionState, connectingTo, repository.lanDevices,
        summaries, autoSyncScheduler.observeActive(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val nearby = (values[2] as Map<String, ScannedDevice>).values
            .sortedByDescending { it.rssi }
        @Suppress("UNCHECKED_CAST")
        ScanUiState(
            scanning = values[0] as Boolean,
            scanError = values[1] as String?,
            nearby = nearby,
            known = values[3] as List<KnownDevice>,
            connection = values[4] as ConnectionState,
            connectingTo = values[5] as String?,
            lanStations = (values[6] as Map<String, *>).keys,
            summaries = values[7] as Map<String, LoggerSummary>,
            backgroundSyncActive = values[8] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    /** Maintainer's walk: one background run over every known logger, LAN then bond, one at a time. */
    fun collectAll() = autoSyncScheduler.collectAll()

    private var scanJob: Job? = null
    private var pruneJob: Job? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanError.value = null
        scanning.value = true
        scanJob = viewModelScope.launch {
            BleScanner.scan(context).collect { ev ->
                when (ev) {
                    is BleScanner.Event.Seen -> {
                        seen.update { it + (ev.device.address to ev.device) }
                        repository.touchSeen(ev.device.address, ev.device.name)
                    }
                    is BleScanner.Event.Failed -> {
                        scanError.value = ev.message
                        stopScan()
                    }
                }
            }
        }
        pruneJob?.cancel()
        pruneJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                val cutoff = System.currentTimeMillis() - STALE_MS
                seen.update { m -> m.filterValues { it.lastSeenEpochMs >= cutoff } }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        pruneJob?.cancel()
        pruneJob = null
        scanning.value = false
    }

    fun connect(address: String, name: String?) {
        stopScan()
        connectingTo.value = address
        repository.connect(address, name)
    }

    fun connectFake() {
        stopScan()
        connectingTo.value = FakeTransport.FAKE_ADDRESS
        repository.connect(FakeTransport.FAKE_ADDRESS, "AQ-6b40 (fake)", fake = true)
    }

    /** Debug: the same fake, behaving like the LAN pipe (payload_max 1024, GET_TOKEN refused). */
    fun connectFakeLan() {
        stopScan()
        connectingTo.value = FakeTransport.FAKE_LAN_ADDRESS
        repository.connect(FakeTransport.FAKE_LAN_ADDRESS, "AQ-6b40 (fake LAN)", fake = true)
    }

    fun clearConnecting() {
        connectingTo.value = null
    }

    fun forget(address: String) {
        viewModelScope.launch { repository.forget(address) }
    }

    override fun onCleared() {
        stopScan()
    }

    companion object {
        const val STALE_MS = 15_000L
    }
}
