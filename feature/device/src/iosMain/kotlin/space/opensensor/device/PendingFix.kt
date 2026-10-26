package space.opensensor.device

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * One location request at a time. A newer wait ends the older one with no cell, a wait gives up after
 * [timeoutMs], and [stop] runs on the main queue through [post], because Core Location expects its
 * manager on the thread that made it. Call [await] and [finish] on the main thread. The cell passes
 * straight through and is never kept here.
 */
internal class PendingFix(
    private val timeoutMs: Long,
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val post: (() -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
) {
    private var continuation: CancellableContinuation<String?>? = null

    suspend fun await(): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { next ->
            continuation?.let { previous ->
                continuation = null
                if (previous.isActive) previous.resume(null)
            }
            continuation = next
            // A cancel can land on any thread. Only the wait it belongs to ends.
            next.invokeOnCancellation {
                post {
                    if (continuation === next) {
                        continuation = null
                        stop()
                    }
                }
            }
            start()
        }
    }

    fun finish(cell: String?) {
        val next = continuation ?: return
        continuation = null
        stop()
        if (next.isActive) next.resume(cell)
    }
}
