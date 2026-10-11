package earth.walkthru.aqsync.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile

/** Progress notifications refresh at reading speed. Each post is a binder call and a shade redraw. */
internal const val NOTIFY_INTERVAL_MS = 1_000L

/**
 * Posts the latest value at most once per [intervalMs] and only while [interactive] is true, so a
 * dark screen costs no notification updates. A value that [isFinal] accepts always goes through,
 * and turning the screen on posts the latest value at once.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> Flow<T>.postPaced(
    intervalMs: Long,
    interactive: Flow<Boolean>,
    isFinal: (T) -> Boolean = { false },
    post: suspend (T) -> Unit,
) {
    // The screen flow never completes, so an end marker stops the loop when the source does.
    val values: Flow<Any?> = (this as Flow<Any?>).onCompletion { if (it == null) emit(End) }
    combine(values, interactive) { value, on -> value to on }
        .takeWhile { (value, _) -> value !== End }
        .filter { (value, on) -> on || isFinal(value as T) }
        .conflate()
        .collect { (value, _) ->
            val v = value as T
            post(v)
            if (!isFinal(v)) delay(intervalMs)
        }
}

private object End

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
