package earth.walkthru.aqsync.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.DeviceFacade
import earth.walkthru.aqsync.data.LogTailState
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.RawFrameRecord
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.opensensor.debug.DebugArchiveTime
import earth.walkthru.opensensor.debug.DebugBlock
import earth.walkthru.opensensor.debug.DebugBlockedException
import earth.walkthru.opensensor.debug.DebugController
import earth.walkthru.opensensor.debug.DebugJson
import earth.walkthru.opensensor.debug.DebugLink
import earth.walkthru.opensensor.debug.DebugLog
import earth.walkthru.opensensor.debug.DebugSource
import earth.walkthru.opensensor.debug.DebugSyncStep
import earth.walkthru.opensensor.debug.DebugSyncTrace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The repository as a [DebugSource] for the shared Debug screen. The long-lived session owns the
 * frame recorder, raw commands run through its mutex, and background sync blocks the raw box.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RepositoryDebugSource(private val repository: DeviceFacade) : DebugSource {
    override val link: Flow<DebugLink> = combine(repository.session, repository.connectionState, repository.activeTransport) { session, conn, kind ->
        DebugLink(conn, kind, session?.transport?.endpoint, (conn as? ConnectionState.Ready)?.let { it.mtu - 3 })
    }
    override val status: Flow<DebugJson?> = combine(repository.statusRaw, repository.status) { raw, timed ->
        raw?.let { DebugJson(it, timed?.receivedAtEpochMs) }
    }
    override val config: Flow<DebugJson?> = repository.config.map { it?.let { c -> DebugJson(c.rawJson, c.atEpochMs) } }
    override val frames: Flow<List<RawFrameRecord>> = repository.session.flatMapLatest { it?.frames?.records ?: flowOf(emptyList()) }
    override val log: Flow<DebugLog> = repository.logTail.map {
        when (it) {
            LogTailState.Idle -> DebugLog.Idle
            LogTailState.Loading -> DebugLog.Loading
            is LogTailState.Loaded -> DebugLog.Loaded(it.tail, it.atEpochMs)
            is LogTailState.Failed -> DebugLog.Failed(it.message)
        }
    }
    override val syncTrace: Flow<DebugSyncTrace?> = combine(repository.archiveTimeSummary, repository.syncDiagnostics) { a, steps ->
        DebugSyncTrace(
            DebugArchiveTime(a.files, a.rows, a.realTimeRows, a.estimatedFiles, a.estimatedRows, a.unplacedFiles, a.unplacedRows, a.unplacedBoots),
            steps.map { DebugSyncStep(it.atEpochMs, it.step, it.detail) },
        )
    }

    /** The connection keeps the config current, the Device screen reads it. */
    override val configOnRequest: Boolean = false

    override fun rawBlock(): DebugBlock? = if (repository.backgroundOwner.value != null) DebugBlock.BACKGROUND_SYNC else null

    override suspend fun rawCommand(request: ByteArray): List<Pair<ByteArray, Frame?>> {
        if (repository.backgroundOwner.value != null) throw DebugBlockedException(DebugBlock.BACKGROUND_SYNC)
        val session = repository.session.value ?: throw DebugBlockedException(DebugBlock.LINK_BUSY)
        return session.rawCommand(request)
    }

    override fun requestStatus() = repository.requestStatus()
    override fun refreshLog() = repository.refreshLogTail()
    override fun refreshConfig() = repository.refreshConfig()
    override fun clearFrames() {
        repository.session.value?.frames?.clear()
    }
}

@HiltViewModel(assistedFactory = DebugViewModel.Factory::class)
class DebugViewModel @AssistedInject constructor(
    manager: DeviceConnectionManager,
    @Assisted deviceAddress: String,
    settings: AppSettings,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): DebugViewModel
    }

    val controller = DebugController(viewModelScope, RepositoryDebugSource(repository))
    val events: SharedFlow<DeviceEvent> = repository.events

    /** The sensor the session talks to, to leave when the link moves to another one. */
    val sessionAddress: StateFlow<String?> = repository.session.map { it?.address }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the stored level loads, so the screen does not pop on the initial value. */
    val developer: StateFlow<Boolean?> = settings.advancedMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
