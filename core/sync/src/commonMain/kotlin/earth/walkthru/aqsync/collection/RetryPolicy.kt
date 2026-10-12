package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.TimeoutPhase
import earth.walkthru.aqsync.link.retryable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When a lost collection tries again. Only [DisconnectCause.retryable] causes retry. [attempts]
 * bounds attempts in a row that saved no byte. No retry starts later than [ceiling] after the
 * request started, so a link that keeps dropping after some progress still ends.
 *
 * A device that cannot be reached at all ([connectPhase] causes) is likely absent, so those get
 * only [connectRetries] retries in a row instead of the full [attempts].
 */
data class RetryPolicy(
    val attempts: Int = 5,
    val firstBackoff: Duration = 2.seconds,
    val maxBackoff: Duration = 60.seconds,
    val ceiling: Duration = 30.minutes,
    /** Retries in a row after a connect-phase failure before the request ends with that cause. */
    val connectRetries: Int = 1,
) {
    init {
        require(attempts >= 1) { "at least one attempt" }
        require(connectRetries >= 0) { "negative connect retries" }
        require(firstBackoff.isPositive()) { "first backoff not positive" }
        require(ceiling.isPositive()) { "ceiling not positive" }
        require(firstBackoff <= maxBackoff) { "first backoff above the max" }
    }

    /**
     * The wait before attempt [next] after [cause] ended attempt `next - 1`, or null to give up.
     * [elapsed] is the time since the request started. [next] counts attempts without progress.
     * [connectFailures] counts connect-phase failures in a row, this one included.
     */
    fun backoff(cause: DisconnectCause, next: Int, elapsed: Duration, connectFailures: Int = 0): Duration? {
        if (!cause.retryable || next > attempts) return null
        if (connectPhase(cause) && connectFailures > connectRetries) return null
        var wait = firstBackoff
        repeat(next - 2) { wait = (wait * 2).coerceAtMost(maxBackoff) }
        return wait.takeIf { elapsed + it <= ceiling }
    }
}

/**
 * True when [cause] ended an attempt before the link opened, so no byte moved: the peer was not
 * found or the open deadline passed.
 */
fun connectPhase(cause: DisconnectCause): Boolean =
    cause == DisconnectCause.Unreachable || (cause is DisconnectCause.Timeout && cause.phase == TimeoutPhase.OPEN)
