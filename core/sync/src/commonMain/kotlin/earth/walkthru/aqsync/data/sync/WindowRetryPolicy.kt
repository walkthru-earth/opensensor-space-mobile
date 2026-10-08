package earth.walkthru.aqsync.data.sync

/**
 * What to do when a `READ` window arrives incomplete (notifications lost between the device and
 * the phone). `READ` is idempotent on an immutable file, so the window is re-read from the last
 * good offset after a short linear backoff. Only when the same window keeps failing is the file
 * given up; the run goes on with the next file.
 */
object WindowRetryPolicy {
    /** Re-reads of one window before the file is given up; 6 attempts in total. */
    const val RETRIES = 5

    /** Linear backoff: 300 ms, 600 ms, … lets a busy link catch up without stalling the sync. */
    const val BACKOFF_MS = 300L

    /** True when [attempt], the count of consecutive losses of one window, gives the file up. */
    fun givesUp(attempt: Int): Boolean = attempt > RETRIES

    /** Wait before re-read number [attempt]. [baseMs] is [BACKOFF_MS] except in tests. */
    fun backoffMs(attempt: Int, baseMs: Long = BACKOFF_MS): Long = baseMs * attempt
}
