package earth.walkthru.aqsync.units

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Mantissas a "nice" step may use, times a power of ten. */
private val NICE_MANTISSAS = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

private const val EPS = 1e-9

private fun tickCount(min: Double, max: Double, step: Double): Long =
    floor(max / step + EPS).toLong() - ceil(min / step - EPS).toLong() + 1

/**
 * The step, 1, 2, 2.5 or 5 times a power of ten, whose multiples inside [min]..[max] come closest
 * to [targetCount] ticks (at least two). A tie goes to the larger step, so the axis stays calm.
 * Returns 0.0 for an empty, inverted or non-finite range.
 */
fun niceTickStep(min: Double, max: Double, targetCount: Int): Double {
    val span = max - min
    if (!span.isFinite() || span <= 0.0) return 0.0
    val target = targetCount.coerceAtLeast(2)
    val exponent = floor(log10(span / (target - 1))).toInt()
    var best = 0.0
    var bestScore = Long.MAX_VALUE
    for (e in exponent - 1..exponent + 1) {
        val magnitude = 10.0.pow(e)
        for (m in NICE_MANTISSAS) {
            val step = m * magnitude
            val score = abs(tickCount(min, max, step) - target)
            if (score < bestScore || (score == bestScore && step > best)) {
                best = step
                bestScore = score
            }
        }
    }
    return best
}

/**
 * Round tick values inside [min]..[max] (both inclusive), on multiples of [niceTickStep]. Values are
 * computed as `k * step`, never accumulated, so they do not drift. A zero-width finite range yields
 * that single value. Anything non-finite yields no ticks.
 */
fun niceTicks(min: Double, max: Double, targetCount: Int): List<Double> {
    if (!min.isFinite() || !max.isFinite() || max < min) return emptyList()
    val step = niceTickStep(min, max, targetCount)
    if (step <= 0.0) return listOf(min)
    val first = ceil(min / step - EPS).toLong()
    val last = floor(max / step + EPS).toLong()
    return (first..last).map { k -> if (k == 0L) 0.0 else k * step }
}

/** Decimals needed to print every tick of [step] exactly (0 for whole steps, 1 for 2.5, 2 for 0.25). */
fun tickDecimals(step: Double): Int {
    if (!step.isFinite() || step <= 0.0) return 0
    for (d in 0..6) {
        val scaled = step * 10.0.pow(d)
        if (abs(scaled - scaled.roundToLong()) < 1e-6 * scaled.coerceAtLeast(1.0)) return d
    }
    return 6
}

/** One y-axis tick: [source] places the gridline on the SI scale, [shown] is the value the label prints. */
data class AxisTick(val source: Double, val shown: Double)

/**
 * Ticks for an SI axis from [minSource] to [maxSource] in [unit], chosen round in the unit shown under
 * [system] and mapped back with [UnitConversion.toSource]. The axis, data and bands stay SI. Only
 * where the gridlines fall changes. Every conversion in the table is increasing, so order holds.
 */
fun displayAxisTicks(minSource: Double, maxSource: Double, unit: String, system: UnitSystem, targetCount: Int): List<AxisTick> {
    val lo = UnitConversion.convert(minSource, unit, system).first
    val hi = UnitConversion.convert(maxSource, unit, system).first
    return niceTicks(minOf(lo, hi), maxOf(lo, hi), targetCount).map { AxisTick(UnitConversion.toSource(it, unit, system), it) }
}
