package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.retryable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When a lost collection tries again. Only [DisconnectCause.retryable] causes retry. [attempts]
 * bounds attempts in a row that saved no byte, and [ceiling] bounds the whole request from its
 * start, so a link that keeps dropping after some progress still ends.
 */
data class RetryPolicy(
    val attempts: Int = 5,
    val firstBackoff: Duration = 2.seconds,
    val maxBackoff: Duration = 60.seconds,
    val ceiling: Duration = 30.minutes,
) {
    init {
        require(attempts >= 1) { "at least one attempt" }
        require(firstBackoff <= maxBackoff) { "first backoff above the max" }
    }

    /**
     * The wait before attempt [next] after [cause] ended attempt `next - 1`, or null to give up.
     * [elapsed] is the time since the request started. [next] counts attempts without progress.
     */
    fun backoff(cause: DisconnectCause, next: Int, elapsed: Duration): Duration? {
        if (!cause.retryable || next > attempts) return null
        var wait = firstBackoff
        repeat(next - 2) { wait = (wait * 2).coerceAtMost(maxBackoff) }
        return wait.takeIf { elapsed + it <= ceiling }
    }
}
