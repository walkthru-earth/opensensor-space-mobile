package space.opensensor.data.ble

/**
 * Conservative process-local admission for all app scans. Android's default is five starts in
 * 30 s, but vendors can configure it. Stops and failed attempts do not refund an admission.
 */
internal class ScanStartBudget(private val windowMs: Long = 30_000, private val maxStarts: Int = 5) {
    private val starts = ArrayDeque<Long>()

    /** Atomically admits a start while leaving [reserveStarts] slots for user discovery. */
    @Synchronized
    fun tryAcquire(nowMs: Long, reserveStarts: Int = 0): Boolean {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) starts.removeFirst()
        if (starts.size + reserveStarts >= maxStarts) return false
        starts.addLast(nowMs)
        return true
    }

    companion object {
        val shared = ScanStartBudget()
    }
}
