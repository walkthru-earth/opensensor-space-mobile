package space.opensensor.family.air

import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.Datastream

/**
 * The quality checks of [QcRules] on the live samples of one thing (issue #91). LIVE is the same row
 * the board queues for storage, so a window of recent samples gives the checks the neighbours they
 * need. [add] returns the flags of the newest sample, and never changes a value.
 *
 * A spike needs the row after it, so the newest sample can never be a spike. The flatline, PM2.5
 * above PM10 and high humidity checks apply as soon as their data is there. Only samples whose
 * sensor reports valid join the window. A sample read again (the same `seq` and `mono`) is not
 * added twice. Use one window per thing, a new thing needs a new window.
 */
class LiveQc(private val capacity: Int = QcRules.FLATLINE_ROWS + 10) {
    private val rows = ArrayDeque<QcRow>()
    private var last: Pair<Long?, Long?>? = null
    private var flags = QcFlags.NONE

    /**
     * Adds [live], received at [receivedAtMs], and returns the flags of the newest sample in the
     * window, or [QcFlags.NONE] when [live] is missing or its sensor is not valid. A sample without
     * `mono` takes [receivedAtMs] as its time.
     */
    fun add(live: LiveReading?, receivedAtMs: Long?): Int {
        if (live == null || !live.pmValid) return QcFlags.NONE
        val sample = live.sample
        val id = sample.seq to sample.mono
        if (id.first != null || id.second != null) {
            if (id == last) return flags
            last = id
        }
        val timeMs = sample.mono?.let { it / 1_000 } ?: receivedAtMs ?: return QcFlags.NONE
        rows.addLast(rowOf(live, timeMs))
        while (rows.size > capacity) rows.removeFirst()
        flags = QcRules.flags(rows.toList()).last()
        return flags
    }

    private fun rowOf(live: LiveReading, timeMs: Long): QcRow {
        val air = live.thing.air
        fun value(ds: Datastream?): Double = ds?.let { live.value(it.name) } ?: Double.NaN
        val bins = air?.bins.orEmpty().filter { live.declares(it.name) }
        val counts = bins.map { live.value(it.name) }
        return QcRow(
            timeMs = timeMs,
            pm1 = value(air?.pm1),
            pm25 = value(air?.pm25),
            pm10 = value(air?.pm10),
            // A board without counts, or a sample missing one of them, skips the checks that read counts.
            counts = if (bins.isEmpty() || counts.any { it == null }) null else IntArray(bins.size) { counts[it]!!.toInt() },
            rh = value(air?.humidity),
        )
    }
}
