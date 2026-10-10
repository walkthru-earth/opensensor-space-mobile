package earth.walkthru.aqsync.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.selects.onTimeout
import kotlin.time.Duration

/**
 * The single use of the experimental `onTimeout` clause (kotlinx.coroutines 1.11.0). A null
 * [timeout] adds no clause, so the select waits only on its other clauses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <R> SelectBuilder<R>.onTimeoutOrNever(timeout: Duration?, block: suspend () -> R) {
    if (timeout != null) onTimeout(timeout.coerceAtLeast(Duration.ZERO), block)
}
