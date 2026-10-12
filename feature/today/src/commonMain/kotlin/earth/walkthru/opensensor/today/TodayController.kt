package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A question Today asks before it changes the device. Only one is open at a time. */
sealed interface TodayDialog {
    /** Finalize the buffered rows with `FLUSH`. */
    data object Flush : TodayDialog
}

/** Shared Today state and confirmed flush actions. Device UTC synchronizes automatically. */
class TodayController(
    private val scope: CoroutineScope,
    private val source: TodaySource,
    detailLevel: Flow<DetailLevel>,
    clockSyncMode: Flow<ClockSyncMode>,
) {
    private val mode = clockSyncMode.stateIn(scope, SharingStarted.Eagerly, ClockSyncMode.ASK)

    val state: StateFlow<TodayUiState> = combine(source.snapshot, detailLevel, mode) { snapshot, level, clock ->
        TodayUiState(snapshot, level, clock)
    }.stateIn(scope, SharingStarted.Eagerly, TodayUiState(source.snapshot.value))

    private val _dialog = MutableStateFlow<TodayDialog?>(null)
    val dialog: StateFlow<TodayDialog?> = _dialog.asStateFlow()

    private val _notices = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Outcomes to show as a snackbar. */
    val notices: SharedFlow<TodayNotice> = _notices.asSharedFlow()

    init {
        scope.launch { source.notices.collect { _notices.tryEmit(it) } }
    }

    fun requestFlush() {
        if (canCommand()) _dialog.value = TodayDialog.Flush
    }

    /** Collect. The engine synchronizes the device clock automatically. */
    fun smartSync() {
        val snapshot = source.snapshot.value
        if (!snapshot.connection.isReady || snapshot.sync.running) return
        source.sync()
    }

    /** The user confirmed the open dialog. Only now does the command go to the device. */
    fun confirm() {
        val open = _dialog.value ?: return
        _dialog.value = null
        when (open) {
            TodayDialog.Flush -> source.flush()
        }
    }

    /** Cancel or Not now. Nothing is sent. */
    fun dismiss() {
        _dialog.value = null
    }

    fun reconnect() = source.reconnect()

    fun disconnect() = source.disconnect()

    private fun canCommand(): Boolean = source.snapshot.value.connection.isReady
}
