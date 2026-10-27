package space.opensensor.today

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.opensensor.family.air.Pm25NowCast
import space.opensensor.settings.DetailLevel

/** A question Today asks before it changes the device. Only one is open at a time. */
sealed interface TodayDialog {
    /** Finalize the buffered rows with `FLUSH`. */
    data object Flush : TodayDialog

    /**
     * Forget this sensor on this phone. Collected files stay. Confirm only closes it, the platform
     * forgets through [TodayAction.Forget] because it also leaves the screen.
     */
    data object Forget : TodayDialog
}

/**
 * Shared Today state and confirmed flush actions. Device UTC synchronizes automatically. [nowCast]
 * is the PM2.5 NowCast of the connected thing ([pm25NowCast]), the quality flags of the live sample
 * come from the snapshots themselves ([withLiveQc]).
 */
class TodayController(
    private val scope: CoroutineScope,
    private val source: TodaySource,
    detailLevel: Flow<DetailLevel>,
    nowCast: Flow<Pm25NowCast?> = flowOf(null),
) {
    val state: StateFlow<TodayUiState> = combine(source.snapshot.withLiveQc(), detailLevel, nowCast) { (snapshot, flags), level, cast ->
        TodayUiState(snapshot, level, nowCast = cast, liveFlags = flags)
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

    /** Opens the Forget confirmation. It needs no link, a sensor out of range can be forgotten too. */
    fun requestForget() {
        _dialog.value = TodayDialog.Forget
    }

    /** Collect. The engine synchronizes the device clock automatically. */
    fun smartSync() {
        if (!source.snapshot.value.thing.canCommand) return
        source.sync()
    }

    /** Stop beside Sync. Only this phone's own run, never one that background work holds. */
    fun stopSync() {
        val snapshot = source.snapshot.value
        if (snapshot.sync.running && !snapshot.heldInBackground) source.stopSync()
    }

    /** The user confirmed the open dialog. Only now does the command go to the device. */
    fun confirm() {
        val open = _dialog.value ?: return
        _dialog.value = null
        when (open) {
            TodayDialog.Flush -> source.flush()
            TodayDialog.Forget -> Unit
        }
    }

    /** Cancel or Not now. Nothing is sent. */
    fun dismiss() {
        _dialog.value = null
    }

    fun reconnect() = source.reconnect()

    fun disconnect() = source.disconnect()

    private fun canCommand(): Boolean = source.snapshot.value.thing.canCommand
}
