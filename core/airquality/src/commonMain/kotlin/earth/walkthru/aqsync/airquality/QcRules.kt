package earth.walkthru.aqsync.airquality

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One reading as the quality checks see it. A missing measurement is `NaN` (or a null [counts]),
 * never 0, so a board without a humidity sensor or without particle counts simply skips the check
 * that needs it.
 *
 * [counts] are the particle count bins of one row, in the same bin order on every row (for a
 * Plantower board `particles_gt03` up to `particles_gt100`, per 0.1 L). The checks only read them.
 */
class QcRow(
    /** Event time in epoch milliseconds. Rows are given in time order. */
    val timeMs: Long,
    val pm1: Double = Double.NaN,
    val pm25: Double = Double.NaN,
    val pm10: Double = Double.NaN,
    val counts: IntArray? = null,
    /** Relative humidity in percent. */
    val rh: Double = Double.NaN,
)

/**
 * The flag bits [QcRules.flags] returns, one `Int` per row. A row with no bit set passed every
 * check that had the data it needs.
 *
 * The bit values become part of the derived tier format once the flags are stored beside the
 * values. A bit keeps its meaning forever. A retired check leaves its bit unused, a new check
 * takes the next free bit, and no value here is ever reused or renumbered.
 */
object QcFlags {
    const val NONE: Int = 0

    /** PM2.5 is above PM10 by more than [QcRules.PM25_ABOVE_PM10_TOLERANCE]. */
    const val PM25_ABOVE_PM10: Int = 1 shl 0

    /** Particle counts unchanged for [QcRules.FLATLINE_ROWS] or more consecutive rows. */
    const val FLATLINE: Int = 1 shl 1

    /** A single row PM mass jump with no matching change in particle counts. */
    const val SPIKE: Int = 1 shl 2

    /** Relative humidity above [QcRules.HIGH_HUMIDITY_PERCENT]. */
    const val HIGH_HUMIDITY: Int = 1 shl 3
}

/**
 * Quality checks for suspect PM readings (issue #91). A pure function of rows, so the indexer, the
 * daily builder and Discover can run it and get the same answer on both phones. It returns flags
 * and never changes a value. Flags sit beside raw values, never instead of them, so a rule change
 * only needs a rebuild of the derived tier.
 *
 * Two rows are neighbours only when the second comes after the first by at most [maxStepMs]. A
 * longer step, a repeated time or a time going back is a gap. Gaps end a flatline run, and a spike
 * needs both neighbours on its side of any gap.
 */
object QcRules {
    /**
     * PM2.5 may exceed PM10 by up to this much, in µg/m³, before it is flagged. Plantower boards
     * report each mass as a whole µg/m³, so two independently rounded values can disagree by up to
     * one unit. Anything beyond that is physically impossible, as PM10 includes PM2.5.
     */
    const val PM25_ABOVE_PM10_TOLERANCE: Double = 1.0

    /** A run of this many rows with identical particle counts is a flatline (issue #91). */
    const val FLATLINE_ROWS: Int = 30

    /**
     * A spike must step away from both neighbours by more than this, in µg/m³. The Plantower
     * PMS5003 datasheet (V2.3) gives a consistency of ±10 µg/m³ from 0 to 100 µg/m³, so two readings
     * that are both inside the spec differ by at most 20.
     */
    const val SPIKE_MIN_STEP: Double = 20.0

    /**
     * Above 100 µg/m³ the same datasheet gives ±10 percent, so the spike step must also exceed this
     * fraction of the larger neighbour.
     */
    const val SPIKE_MIN_RATIO: Double = 0.2

    /**
     * Plantower mass is computed from the same particle counts, so a real step in mass moves at
     * least one count bin the same way. A count bin matches when its relative change is at least
     * this fraction of the relative mass change. Any matching bin clears the row, so the check
     * fires only when the counts stay essentially still.
     */
    const val SPIKE_COUNT_MATCH: Double = 0.5

    /**
     * Rows above this relative humidity, in percent, are flagged (issue #91). Plantower optical
     * sensors read high when particles take up water, a bias that grows steeply above about
     * 75 percent RH (Jayaratne et al. 2018, Atmos. Meas. Tech. 11, 4883). 85 percent marks the
     * range where that growth and condensation dominate.
     */
    const val HIGH_HUMIDITY_PERCENT: Double = 85.0

    /** The default gap is this many typical sampling steps, so one or two dropped rows do not end a run. */
    const val GAP_FACTOR: Long = 3

    /**
     * One flag bitmask of [QcFlags] per row of [rows], in the same order.
     *
     * [maxStepMs] defaults to [GAP_FACTOR] times the median positive step between rows, so it
     * follows the board's own sampling interval.
     */
    fun flags(rows: List<QcRow>, maxStepMs: Long = GAP_FACTOR * typicalStepMs(rows)): IntArray {
        val out = IntArray(rows.size)
        val linked = BooleanArray(rows.size) { i ->
            i > 0 && (rows[i].timeMs - rows[i - 1].timeMs).let { it in 1..maxStepMs }
        }
        for ((i, row) in rows.withIndex()) {
            if (row.pm25.isFinite() && row.pm10.isFinite() && row.pm25 - row.pm10 > PM25_ABOVE_PM10_TOLERANCE) {
                out[i] = out[i] or QcFlags.PM25_ABOVE_PM10
            }
            if (row.rh.isFinite() && row.rh > HIGH_HUMIDITY_PERCENT) out[i] = out[i] or QcFlags.HIGH_HUMIDITY
        }
        markFlatlines(rows, linked, out)
        markSpikes(rows, linked, out, QcRow::pm1)
        markSpikes(rows, linked, out, QcRow::pm25)
        markSpikes(rows, linked, out, QcRow::pm10)
        return out
    }

    /** The median positive step between consecutive rows, or 0 when there is none. */
    fun typicalStepMs(rows: List<QcRow>): Long {
        val steps = (1 until rows.size).map { rows[it].timeMs - rows[it - 1].timeMs }.filter { it > 0 }.sorted()
        return if (steps.isEmpty()) 0 else steps[steps.size / 2]
    }

    private fun markFlatlines(rows: List<QcRow>, linked: BooleanArray, out: IntArray) {
        var start = 0
        fun close(end: Int) {
            if (end - start >= FLATLINE_ROWS) for (k in start until end) out[k] = out[k] or QcFlags.FLATLINE
        }
        for (i in rows.indices) {
            val counts = rows[i].counts
            if (counts == null) {
                close(i)
                start = i + 1
            } else if (i > start && !(linked[i] && counts.contentEquals(rows[i - 1].counts))) {
                close(i)
                start = i
            }
        }
        close(rows.size)
    }

    private fun markSpikes(rows: List<QcRow>, linked: BooleanArray, out: IntArray, mass: (QcRow) -> Double) {
        for (i in 1 until rows.size - 1) {
            if (!linked[i] || !linked[i + 1]) continue
            val before = mass(rows[i - 1])
            val at = mass(rows[i])
            val after = mass(rows[i + 1])
            if (!before.isFinite() || !at.isFinite() || !after.isFinite()) continue
            val up = at - before
            val back = at - after
            if (up == 0.0 || back == 0.0 || (up > 0) != (back > 0)) continue
            val threshold = max(SPIKE_MIN_STEP, SPIKE_MIN_RATIO * max(before, after))
            if (min(abs(up), abs(back)) <= threshold) continue
            val massChange = abs(up) / max(abs(before), abs(at))
            if (countsMatch(rows[i - 1].counts, rows[i].counts, up > 0, massChange)) continue
            out[i] = out[i] or QcFlags.SPIKE
        }
    }

    /**
     * Whether a count bin moves with the mass from [from] to [to]. Without counts on both rows the
     * check cannot tell, so it treats them as matching and does not flag.
     */
    private fun countsMatch(from: IntArray?, to: IntArray?, rising: Boolean, massChange: Double): Boolean {
        if (from == null || to == null || from.size != to.size) return true
        for (k in from.indices) {
            val step = to[k] - from[k]
            if (step == 0 || (step > 0) != rising) continue
            val change = abs(step).toDouble() / max(max(from[k], to[k]), 1)
            if (change >= SPIKE_COUNT_MATCH * massChange) return true
        }
        return false
    }
}
