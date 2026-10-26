package space.opensensor.data.background

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Work for a `goAsync()` broadcast. The system expects a receiver to finish in under 10 s
 * (developer.android.com, "Broadcasts overview"), so [block] runs in the injected application
 * scope under [timeoutMs], and [finish] (`PendingResult.finish()`) is called on every path,
 * including timeout, failure and cancellation. A failure is logged by class name only and never
 * reaches the scope's other children.
 */
internal fun launchReceiverWork(
    scope: CoroutineScope,
    tag: String,
    finish: () -> Unit,
    timeoutMs: Long = RECEIVER_BUDGET_MS,
    block: suspend () -> Unit,
): Job = scope.launch {
    try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        Log.w(tag, "broadcast work exceeded ${timeoutMs / 1000} s; finishing the broadcast")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "broadcast work failed: ${e::class.simpleName}")
    } finally {
        finish()
    }
}

/** Below the ~10 s `goAsync()` allowance, with room for `finish()` to reach the system. */
internal const val RECEIVER_BUDGET_MS = 9_000L
