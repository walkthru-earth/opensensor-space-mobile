package earth.walkthru.aqsync.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.ArchiveTimeSummary
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.TimedLive
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.TransportPreference
import earth.walkthru.aqsync.data.ble.BleConnectionFailure
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.toShared
import earth.walkthru.opensensor.today.TodayArchive
import earth.walkthru.opensensor.today.TodayController
import earth.walkthru.opensensor.today.TodayNotice
import earth.walkthru.opensensor.today.TodayReconnect
import earth.walkthru.opensensor.today.TodaySnapshot
import earth.walkthru.opensensor.today.TodaySource
import earth.walkthru.opensensor.today.todaySoFar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Android host of the shared Today ([TodayController], `:feature:today`). The screen, its cards and
 * the confirmation dialogs are shared with iOS. This class only adapts the device's
 * [DeviceConnectionRepository] to the [TodaySource] port and reads the settings store.
 */
@HiltViewModel(assistedFactory = TodayViewModel.Factory::class)
class TodayViewModel @AssistedInject constructor(
    manager: DeviceConnectionManager,
    @Assisted private val deviceAddress: String,
    settings: AppSettings,
    history: HistoryRepository,
    knownDeviceDao: KnownDeviceDao,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): TodayViewModel
    }

    private val repository = manager.controller(deviceAddress)

    /** Info and errors stay Android's own snackbar lines. The clock question goes to the controller. */
    val events: SharedFlow<DeviceEvent> = repository.events

    private val enrolled = knownDeviceDao.observeAll().map { devices -> devices.firstOrNull { it.address == deviceAddress } }

    /** Only a saved token for this enrolled station and a current mDNS result permit LAN-only reconnect. */
    val lanReconnectAvailable: StateFlow<Boolean> = combine(enrolled, repository.lanDevices) { known, discovered ->
        hasLanReconnectTarget(known, discovered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val source = RepositoryTodaySource(
        scope = viewModelScope,
        repository = repository,
        address = deviceAddress,
        enrolled = enrolled,
        todaySoFar = todaySoFar(repository.info.map { it?.station }, history::observeHourlyDay),
    )

    val controller = TodayController(
        scope = viewModelScope,
        source = source,
        detailLevel = settings.detailLevel,
        clockSyncMode = settings.clockSyncMode.map { it.toShared() },
    )

    fun reconnectLanOnly() = repository.connect(deviceAddress, controller.state.value.snapshot.name, preference = TransportPreference.LAN_ONLY)
}

/** [DeviceConnectionRepository] as the shared Today port. Every verb is a call the old screen made. */
private class RepositoryTodaySource(
    scope: CoroutineScope,
    private val repository: DeviceConnectionRepository,
    private val address: String,
    enrolled: Flow<KnownDevice?>,
    todaySoFar: Flow<TodaySoFar?>,
) : TodaySource {
    private val fakeEndpoint = address == FakeTransport.FAKE_ADDRESS || address == FakeTransport.FAKE_LAN_ADDRESS

    override val snapshot: StateFlow<TodaySnapshot> = combine(
        repository.session, repository.connectionState, repository.info, repository.status, repository.live,
        repository.sync, repository.reconnecting, repository.activeTransport, todaySoFar, repository.archiveTimeSummary,
        enrolled, repository.config,
    ) { values ->
        val session = values[0] as DeviceSession?
        val connection = values[1] as ConnectionState
        val status = values[3] as TimedStatus?
        val live = values[4] as TimedLive?
        val reconnect = values[6] as ReconnectState?
        val archive = values[9] as ArchiveTimeSummary
        TodaySnapshot(
            address = session?.address ?: address,
            name = session?.transport?.displayName ?: (values[10] as KnownDevice?)?.name,
            connection = connection,
            // From the repository's own flow, not from the session object, see activeTransport.
            transport = values[7] as TransportKind?,
            info = values[2] as DeviceInfo?,
            status = status?.status,
            statusAtEpochMs = status?.receivedAtEpochMs,
            live = live?.sample,
            liveAtEpochMs = live?.receivedAtEpochMs,
            sync = values[5] as SyncProgress,
            archive = TodayArchive(files = archive.files, estimatedRows = archive.estimatedRows, unplacedRows = archive.unplacedRows),
            config = values[11] as ConfigSnapshot?,
            reconnecting = reconnect?.let { TodayReconnect(it.attempt, it.maxAttempts, it.reason) },
            needsPairingRepair = BleConnectionFailure.needsPairingRepair(connection),
            todaySoFar = values[8] as TodaySoFar?,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TodaySnapshot(address = address))

    /** Only the clock question. Info and error lines are shown from [DeviceConnectionRepository.events]. */
    override val notices: Flow<TodayNotice> = emptyFlow()

    /** Collection applies the automatic clock rule. */
    override fun sync() = repository.smartSync()

    override fun setTime() = repository.setTimeFromPhone()

    override fun flush() = repository.flush()

    override fun reconnect() = repository.connect(address, snapshot.value.name, fake = fakeEndpoint)

    override fun disconnect() = repository.disconnect()
}
