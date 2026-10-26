package space.opensensor.data.sync

import kotlin.time.TimeSource

/** Rolling throughput over the last few seconds. */
class RateMeter(time: TimeSource.WithComparableMarks) {
    private val origin = time.markNow()
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun update(bytesDone: Long): Double {
        val now = origin.elapsedNow().inWholeNanoseconds
        // A corrupt partial can force a clean re-read, so the verified transfer progress
        // legitimately moves back to zero. Start a new rate window instead of showing a
        // negative throughput while that earlier attempt ages out of the sample window.
        if (samples.isNotEmpty() && bytesDone < samples.last().second) samples.clear()
        samples.addLast(now to bytesDone)
        while (samples.size > 2 && now - samples.first().first > WINDOW_NS) samples.removeFirst()
        val (t0, b0) = samples.first()
        val dt = (now - t0) / 1e9
        return if (dt <= 0.0) 0.0 else (bytesDone - b0) / dt
    }

    companion object {
        const val WINDOW_NS = 3_000_000_000L
    }
}
