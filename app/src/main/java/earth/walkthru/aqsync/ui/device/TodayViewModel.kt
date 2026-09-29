package earth.walkthru.aqsync.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.ArchiveTimeSummary
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.TimedLive
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.ArchiveDayQuery
import earth.walkthru.aqsync.data.history.ArchiveQueryResult
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.Resolution
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.history.archiveQueryResults
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class TodayUiState(
    val address: String? = null,
    val name: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val info: DeviceInfo? = null,
    val status: TimedStatus? = null,
    val live: TimedLive? = null,
    val sync: SyncProgress = SyncProgress(),
    val isFake: Boolean = false,
    val transport: TransportKind? = null,
    val advancedMode: Boolean = false,
    val reconnecting: ReconnectState? = null,
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
    val clockSyncMode: ClockSyncMode = ClockSyncMode.ASK,
    val archiveTime: ArchiveTimeSummary = ArchiveTimeSummary(),
    /** Today (local calendar day) for this station from the hourly tier, once the phone archive has timed rows for it. */
    val todaySoFar: TodaySoFar? = null,
)

@HiltViewModel(assistedFactory = TodayViewModel.Factory::class)
class TodayViewModel @AssistedInject constructor(
    manager: DeviceConnectionManager,
    @Assisted private val deviceAddress: String,
    settings: AppSettings,
    bucketDao: MetricBucketDao,
    knownDeviceDao: KnownDeviceDao,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)
    private val enrolledDevice = knownDeviceDao.observeAll().map { devices -> devices.firstOrNull { it.address == deviceAddress } }
    private val fakeEndpoint = deviceAddress == FakeTransport.FAKE_ADDRESS || deviceAddress == FakeTransport.FAKE_LAN_ADDRESS

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): TodayViewModel
    }

    val events: SharedFlow<DeviceEvent> = repository.events
    private val dayStart = localDayStart().stateIn(viewModelScope, SharingStarted.Eagerly, currentDayStart())

    /**
     * Today so far for the connected station — the **local** calendar day, like the History Day view,
     * folded from the hourly tier (UTC-aligned buckets; a whole-hour offset zone folds exactly, a
     * half-hour zone is off by at most one bucket edge). Re-emits as the deriver rebuilds, and rolls
     * over at local midnight while the screen stays open ([localDayStart] ticks once a minute).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val todaySoFar = combine(repository.info.map { it?.station }.distinctUntilChanged(), dayStart) { station, start -> ArchiveDayQuery(station, start) }
        .archiveQueryResults { query ->
            val station = query.station
            if (station == null) {
                flowOf(null)
            } else {
                bucketDao.observe(station, Metrics.primary.id, Resolution.HOUR.seconds, query.dayStartS, query.dayStartS + 86_400).map { buckets ->
                    val valid = buckets.sumOf { it.validRows }
                    if (valid == 0) {
                        null
                    } else {
                        val mean = buckets.sumOf { it.mean.toDouble() * it.validRows } / valid
                        TodaySoFar(mean.toFloat(), valid, (valid.toFloat() / Resolution.DAY.expectedRows).coerceIn(0f, 1f))
                    }
                }
            }
        }

    val uiState: StateFlow<TodayUiState> = combine(
        repository.session, repository.connectionState, repository.info, repository.status, repository.live, repository.sync,
        settings.detailLevel, repository.reconnecting, repository.activeTransport, todaySoFar, settings.clockSyncMode, repository.archiveTimeSummary, enrolledDevice, dayStart,
    ) { values ->
        val session = values[0] as earth.walkthru.aqsync.data.ble.DeviceSession?
        val info = values[2] as DeviceInfo?

        @Suppress("UNCHECKED_CAST")
        val today = values[9] as ArchiveQueryResult<ArchiveDayQuery, TodaySoFar>
        TodayUiState(
            address = session?.address ?: deviceAddress,
            name = session?.transport?.displayName ?: (values[12] as earth.walkthru.aqsync.data.local.database.KnownDevice?)?.name,
            connection = values[1] as ConnectionState,
            info = info,
            status = values[3] as TimedStatus?,
            live = values[4] as TimedLive?,
            sync = values[5] as SyncProgress,
            isFake = repository.isFake || fakeEndpoint,
            // From the repository's own flow, not from the session object: see activeTransport.
            transport = values[8] as TransportKind?,
            advancedMode = (values[6] as DetailLevel).developer,
            detailLevel = values[6] as DetailLevel,
            reconnecting = values[7] as ReconnectState?,
            todaySoFar = today.forQuery(ArchiveDayQuery(info?.station, values[13] as Long)),
            clockSyncMode = values[10] as ClockSyncMode,
            archiveTime = values[11] as ArchiveTimeSummary,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /** Start of the current local day (epoch seconds), re-evaluated every minute so midnight moves the window. */
    private fun localDayStart(): Flow<Long> = flow {
        while (true) {
            emit(currentDayStart())
            delay(60_000)
        }
    }.distinctUntilChanged()

    private fun currentDayStart(): Long = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    fun smartSync(clockConfirmed: Boolean = false) = repository.smartSync(uiState.value.clockSyncMode, clockConfirmed)
    fun setTimeFromPhone() = repository.setTimeFromPhone()
    fun flush() = repository.flush()
    fun requestStatus() = repository.requestStatus()
    fun disconnect() = repository.disconnect()
    fun reconnect() {
        repository.connect(deviceAddress, uiState.value.name, fake = fakeEndpoint)
    }
}
