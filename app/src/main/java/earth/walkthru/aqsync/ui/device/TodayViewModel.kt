package earth.walkthru.aqsync.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.TimedLive
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.Resolution
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

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
    /** Today (local calendar day) for this station from the hourly tier, once the phone archive has timed rows for it. */
    val todaySoFar: TodaySoFar? = null,
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    settings: AppSettings,
    bucketDao: MetricBucketDao,
) : ViewModel() {

    val events: SharedFlow<DeviceEvent> = repository.events

    /**
     * Today so far for the connected station — the **local** calendar day, like the History Day view,
     * folded from the hourly tier (UTC-aligned buckets; a whole-hour offset zone folds exactly, a
     * half-hour zone is off by at most one bucket edge). Re-emits as the deriver rebuilds, and rolls
     * over at local midnight while the screen stays open ([localDayStart] ticks once a minute).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val todaySoFar = combine(repository.info.map { it?.station }.distinctUntilChanged(), localDayStart()) { station, dayStart -> station to dayStart }
        .flatMapLatest { (station, dayStart) ->
            if (station == null) {
                flowOf(null)
            } else {
                bucketDao.observe(station, Metrics.primary.id, Resolution.HOUR.seconds, dayStart, dayStart + 86_400).map { buckets ->
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
        settings.detailLevel, repository.reconnecting, repository.activeTransport, todaySoFar,
    ) { values ->
        val session = values[0] as earth.walkthru.aqsync.data.ble.DeviceSession?
        TodayUiState(
            address = session?.address,
            name = session?.transport?.displayName,
            connection = values[1] as ConnectionState,
            info = values[2] as DeviceInfo?,
            status = values[3] as TimedStatus?,
            live = values[4] as TimedLive?,
            sync = values[5] as SyncProgress,
            isFake = repository.isFake,
            // From the repository's own flow, not from the session object: see activeTransport.
            transport = values[8] as TransportKind?,
            advancedMode = (values[6] as DetailLevel).developer,
            detailLevel = values[6] as DetailLevel,
            reconnecting = values[7] as ReconnectState?,
            todaySoFar = values[9] as TodaySoFar?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /** Start of the current local day (epoch seconds), re-evaluated every minute so midnight moves the window. */
    private fun localDayStart(): Flow<Long> = flow {
        while (true) {
            emit(LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toEpochSecond())
            delay(60_000)
        }
    }.distinctUntilChanged()

    fun setTimeFromPhone() = repository.setTimeFromPhone()
    fun flush() = repository.flush()
    fun requestStatus() = repository.requestStatus()
    fun disconnect() = repository.disconnect()
    fun reconnect() {
        val addr = uiState.value.address ?: return
        repository.connect(addr, uiState.value.name, fake = uiState.value.isFake)
    }
}
