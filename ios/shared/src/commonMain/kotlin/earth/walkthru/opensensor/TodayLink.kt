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

/** How one command ended, from `SensorSync.command`. Swift hands it back to [TodayLink.commandFinished] unread. */
class CommandResult internal constructor(internal val notice: TodayNotice) {
    override fun toString(): String = "CommandResult($notice)"
}

/**
 * The Today side of the Bluetooth companion: whether a command is in flight and the outcomes Today
 * shows. Swift reports through it on the main thread. It never holds a PIN, token or Wi-Fi secret.
 */
class TodayLink {
    internal var busy by mutableStateOf(false)
        private set

    private val _notices = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val notices: SharedFlow<TodayNotice> = _notices.asSharedFlow()

    /** The command was written to a fresh link. */
    fun commandStarted() {
        busy = true
    }

    /** Nothing was sent: not connected, or the link is syncing or asking for Wi-Fi access. */
    fun commandRefused() {
        _notices.tryEmit(TodayNotice.Busy)
    }

    /** The command ended, also when the connection closed under it. */
    fun commandFinished(result: CommandResult) {
        busy = false
        _notices.tryEmit(result.notice)
    }
}
