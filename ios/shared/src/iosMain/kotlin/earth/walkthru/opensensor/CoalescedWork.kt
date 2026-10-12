package earth.walkthru.opensensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs [work] on [scope] after each [request], never inside the caller. Requests that arrive while
 * a run is under way collapse into one more run. A failed run logs its class only and later requests
 * still run, so one bad file never stops the follow-up for good.
 */
internal class CoalescedWork(scope: CoroutineScope, private val name: String, private val work: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("$name retry needed: ${e::class.simpleName}")
                }
            }
        }
    }

    /** Asks for one run. Returns at once and is safe from any thread. */
    fun request() {
        requests.trySend(Unit)
    }
}
