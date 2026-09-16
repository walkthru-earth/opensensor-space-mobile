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
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    settings: AppSettings,
) : ViewModel() {

    val events: SharedFlow<DeviceEvent> = repository.events

    val uiState: StateFlow<TodayUiState> = combine(
        repository.session, repository.connectionState, repository.info, repository.status, repository.live, repository.sync,
        settings.advancedMode, repository.reconnecting,
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
            transport = session?.transport?.kind,
            advancedMode = values[6] as Boolean,
            reconnecting = values[7] as ReconnectState?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun setTimeFromPhone() = repository.setTimeFromPhone()
    fun flush() = repository.flush()
    fun requestStatus() = repository.requestStatus()
    fun disconnect() = repository.disconnect()
    fun reconnect() {
        val addr = uiState.value.address ?: return
        repository.connect(addr, uiState.value.name, fake = uiState.value.isFake)
    }
}
