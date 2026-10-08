package earth.walkthru.aqsync.data.coroutines

import kotlinx.coroutines.CancellationException
import javax.inject.Qualifier
import kotlin.time.TimeSource

/**
 * [runCatching] for code that calls suspend functions. Cancellation is rethrown so a cancelled
 * caller stops instead of carrying on with a failed [Result]. Every other throwable is captured,
 * like [runCatching].
 *
 * Note that a [kotlinx.coroutines.TimeoutCancellationException] thrown by a `withTimeout` inside
 * [block] is a [CancellationException] too and is rethrown. Use `withTimeoutOrNull` for a
 * best-effort bounded call.
 */
inline fun <R> suspendRunCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Wall-clock time for timestamps that are stored or shown. Use [TimeSource] for durations. */
fun interface WallClock {
    fun nowEpochMs(): Long

    companion object {
        val System: WallClock = WallClock { java.lang.System.currentTimeMillis() }
    }
}

/** Monotonic time for rates and timeouts; tests pass a `TestTimeSource` or the test scheduler's. */
typealias MonotonicTime = TimeSource.WithComparableMarks

/** Dispatcher for blocking I/O (Room, files, sockets). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Process-lifetime scope with a [kotlinx.coroutines.SupervisorJob] and a logging exception handler.
 * Work launched here outlives screens and broadcast receivers.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
