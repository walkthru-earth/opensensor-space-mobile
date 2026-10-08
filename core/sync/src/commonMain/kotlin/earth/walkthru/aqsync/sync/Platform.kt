package earth.walkthru.aqsync.sync

import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * A reentrant lock for short, non-suspending critical sections (a ring buffer, a small set). JVM
 * uses a monitor and iOS an `NSRecursiveLock`. Suspending code uses a coroutine `Mutex` instead.
 */
internal expect class SyncLock() {
    fun <T> withLock(block: () -> T): T
}

/** Wall-clock milliseconds for timestamps that are stored or shown. */
internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds for intervals. Only differences between two readings mean anything. */
internal fun monotonicMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
