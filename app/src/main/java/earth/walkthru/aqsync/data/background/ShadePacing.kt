package earth.walkthru.aqsync.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Progress notifications refresh at reading speed. Each post is a binder call and a shade redraw. */
internal const val NOTIFY_INTERVAL_MS = 1_000L

/**
 * Posts the latest value at most once per [intervalMs] and only while [interactive] is true, so a
 * dark screen costs no notification updates. A value that [isFinal] accepts always goes through,
 * and turning the screen on refreshes the latest value subject to the existing pacing interval.
 */
internal suspend fun <T> Flow<T>.postPaced(
    intervalMs: Long,
    interactive: Flow<Boolean>,
    isFinal: (T) -> Boolean = { false },
    post: suspend (T) -> Unit,
) = coroutineScope {
    val on = MutableStateFlow(false)
    val latest = MutableStateFlow<ShadeValue<T>?>(null)
    val values = Channel<T>(Channel.CONFLATED)
    val screen = launch(start = CoroutineStart.UNDISPATCHED) {
        interactive.distinctUntilChanged().collect { state ->
            on.value = state
            if (state) latest.value?.let { values.trySend(it.value) }
        }
    }
    val source = launch {
        try {
            this@postPaced.collect { value ->
                latest.value = ShadeValue(value)
                values.send(value)
            }
            // Closure preserves the final buffered value, unlike a conflated completion marker.
            values.close()
        } catch (cause: Throwable) {
            values.close(cause)
            throw cause
        }
    }
    try {
        for (value in values) {
            // Read after the pacing delay, so progress queued while on cannot post while dark.
            if (on.value || isFinal(value)) {
                post(value)
                if (!isFinal(value)) delay(intervalMs)
            }
        }
    } finally {
        // Keep screen observation alive until buffered progress has been drained or cancelled.
        source.cancel()
        screen.cancel()
        values.cancel()
    }
}

private data class ShadeValue<T>(val value: T)

/** `PowerManager.isInteractive`, kept current by the screen on and off broadcasts. */
internal fun screenInteractive(context: Context): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    // System broadcasts still reach a receiver that is not exported.
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    // Read after registering, so a change in between is not lost.
    trySend(context.getSystemService(PowerManager::class.java)?.isInteractive ?: true)
    awaitClose { runCatching { context.unregisterReceiver(receiver) } }
}.distinctUntilChanged()
