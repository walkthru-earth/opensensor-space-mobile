package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.sync.ClockQuality
import earth.walkthru.aqsync.data.sync.ClockSyncAction
import earth.walkthru.aqsync.data.sync.SmartSyncPolicy
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
    /** Send this phone's time with `SET_TIME`. */
    data object SetTime : TodayDialog

    /** Finalize the buffered rows with `FLUSH`. */
    data object Flush : TodayDialog

    /** Smart Sync wants to send this phone's time first, because of [quality]. */
    data class ClockConsent(val quality: ClockQuality) : TodayDialog
}

/**
 * The shared Today logic, platform free. It combines the [source] snapshot with the detail level and
 * the Smart Sync clock consent from the settings store, and owns the confirmation dialogs. Nothing
 * that changes the device is sent before the user confirmed it: `SET_TIME`, `FLUSH`, and the clock
 * step of Smart Sync when the mode is ASK. Android hosts it in a ViewModel, iOS in AppRoot.
 */
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

    /** Outcomes to show as a snackbar. A clock question opens its dialog instead. */
    val notices: SharedFlow<TodayNotice> = _notices.asSharedFlow()

    init {
        scope.launch {
            source.notices.collect { notice ->
                if (notice is TodayNotice.ClockConfirmationRequired) {
                    _dialog.value = TodayDialog.ClockConsent(notice.quality)
                } else {
                    _notices.tryEmit(notice)
                }
            }
        }
    }

    fun requestSetTime() {
        if (canCommand()) _dialog.value = TodayDialog.SetTime
    }

    fun requestFlush() {
        if (canCommand()) _dialog.value = TodayDialog.Flush
    }

    /**
     * Smart Sync. With ASK and a clock that needs the phone's time, the consent dialog opens and
     * nothing is sent. Otherwise the platform syncs, with AUTOMATIC allowing the clock step.
     */
    fun smartSync() {
        val snapshot = source.snapshot.value
        if (!snapshot.connection.isReady || snapshot.sync.running) return
        val policyMode = mode.value.toPolicy()
        val decision = SmartSyncPolicy.decide(snapshot.status, policyMode)
        if (decision.action == ClockSyncAction.ASK_USER) {
            _dialog.value = TodayDialog.ClockConsent(decision.quality)
        } else {
            source.sync(policyMode, clockConfirmed = false)
        }
    }

    /** The user confirmed the open dialog. Only now does the command go to the device. */
    fun confirm() {
        val open = _dialog.value ?: return
        _dialog.value = null
        when (open) {
            TodayDialog.SetTime -> source.setTime()
            TodayDialog.Flush -> source.flush()
            is TodayDialog.ClockConsent -> source.sync(mode.value.toPolicy(), clockConfirmed = true)
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
