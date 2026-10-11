package earth.walkthru.opensensor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.walkthru.opensensor.today.TodayCommand
import earth.walkthru.opensensor.today.TodayNotice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A device-changing command Today sends after the user confirmed it. Swift sees `.setTime` and `.flush`. */
enum class SensorCommand {
    /** `SET_TIME` with this phone's clock. */
    SET_TIME,

    /** `FLUSH`, finalizing the buffered rows into a short file. */
    FLUSH,
    ;

    internal val today: TodayCommand get() = when (this) {
        SET_TIME -> TodayCommand.SET_TIME
        FLUSH -> TodayCommand.FLUSH
    }
}

/**
 * How one command ended, from `SensorSync.command`. Swift hands it back to [TodayLink.commandEnded]
 * unread. A null [notice] means it was stopped, which Today does not report as a failure.
 */
class CommandResult internal constructor(internal val notice: TodayNotice?) {
    val clockSynchronized: Boolean get() = notice == TodayNotice.ClockSet
    val stopped: Boolean get() = notice == null
    override fun toString(): String = "CommandResult($notice)"
}

/**
 * The Today side of the Bluetooth companion: whether a command is in flight and the outcomes Today
 * shows. Swift reports through it on the main thread. It never holds a PIN, token or Wi-Fi secret.
 */
class TodayLink {
    internal var busy by mutableStateOf(false)
        private set

    /** Native LAN adapter checks whether another Today command owns the shared action slot. */
    val commandBusy: Boolean get() = busy

    private val _notices = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val notices: SharedFlow<TodayNotice> = _notices.asSharedFlow()

    /** The command was written to a fresh link. */
    fun commandStarted() {
        busy = true
    }

    /** Nothing was sent: not connected, or the link is syncing or asking for Wi-Fi access. A refused sync reports it too. */
    fun commandRefused() {
        _notices.tryEmit(TodayNotice.Busy)
    }

    /** How the command ended, kept until [linkIdle]. Today stays busy until then. */
    private var ended: TodayNotice? = null
    private var endedPending = false

    /**
     * The command ended and handed its link back. A write it queued last, such as the status
     * request after `SET_TIME`, may still be in flight, so the outcome waits for [linkIdle]. Smart
     * Sync starts its file sync on that outcome, and a sync started earlier would be refused.
     */
    fun commandEnded(result: CommandResult) {
        ended = result.notice
        endedPending = true
    }

    /** No write or read is queued or in flight. Also called when the connection closes. */
    fun linkIdle() {
        if (!endedPending) return
        val notice = ended
        ended = null
        endedPending = false
        busy = false
        notice?.let(_notices::tryEmit)
    }

    /** The command ended on an idle link. */
    fun commandFinished(result: CommandResult) {
        commandEnded(result)
        linkIdle()
    }

    /** A LAN socket closed while it owned a command; release the busy state immediately. */
    fun commandInterrupted(command: SensorCommand) {
        ended = null
        endedPending = false
        busy = false
        _notices.tryEmit(TodayNotice.Failed(command.today))
    }
}
