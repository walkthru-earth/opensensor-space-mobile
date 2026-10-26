package earth.walkthru.aqsync.data.history

import space.opensensor.model.Aggregate

/**
 * One metric's running rollup under its [aggregate] (`opensensor:aggregate`), for the folds that work
 * one series at a time. [Aggregator] keeps the same rules in primitive arrays. `mean` is the mean, `sum`
 * the total, `minimum` and `maximum` the extremes, `mode` the most frequent value, and `point` (and any
 * aggregate this build does not know) the value at the latest time.
 */
internal class Rollup(private val aggregate: Aggregate) {
    var n: Int = 0
        private set
    private var sum = 0.0
    private var min = Float.POSITIVE_INFINITY
    private var max = Float.NEGATIVE_INFINITY
    private var lastAt = Long.MIN_VALUE
    private var last = 0f
    private var modes: HashMap<Float, Int>? = null

    /** Adds a finite [value] seen at [at], any increasing key such as a time or a row index. */
    fun add(value: Double, at: Long) {
        n++
        sum += value
        val f = value.toFloat()
        if (f < min) min = f
        if (f > max) max = f
        if (at >= lastAt) {
            lastAt = at
            last = f
        }
        if (aggregate == Aggregate.MODE) (modes ?: HashMap<Float, Int>().also { modes = it }).let { it[f] = (it[f] ?: 0) + 1 }
    }

    /** The rolled value, null when nothing was added. */
    val value: Float?
        get() = if (n == 0) {
            null
        } else {
            when (aggregate) {
                Aggregate.MEAN -> (sum / n).toFloat()
                Aggregate.SUM -> sum.toFloat()
                Aggregate.MINIMUM -> min
                Aggregate.MAXIMUM -> max
                Aggregate.MODE -> modes?.let(::modeOf) ?: last
                else -> last
            }
        }

    /** As a [MetricAgg], null when nothing was added. */
    fun agg(settling: Int = 0): MetricAgg? = value?.let { MetricAgg(n, it, min, max, settling) }
}

/** One partial rollup to [combine], the aggregate [value] of [n] rows, the latest of them at [at]. */
internal class Partial(val value: Float, val n: Long, val at: Long)

/**
 * [parts] rolled up again under [aggregate], null when none holds rows. Means weigh by rows, sums add,
 * extremes keep the extreme, a mode keeps the value behind the most rows and a point keeps the latest.
 */
internal fun combine(aggregate: Aggregate, parts: List<Partial>): Float? {
    val held = parts.filter { it.n > 0 }
    if (held.isEmpty()) return null
    return when (aggregate) {
        Aggregate.MEAN -> (held.sumOf { it.value.toDouble() * it.n } / held.sumOf { it.n }).toFloat()
        Aggregate.SUM -> held.sumOf { it.value.toDouble() }.toFloat()
        Aggregate.MINIMUM -> held.minOf { it.value }
        Aggregate.MAXIMUM -> held.maxOf { it.value }
        Aggregate.MODE -> held.groupBy { it.value }.maxWithOrNull(compareBy<Map.Entry<Float, List<Partial>>> { e -> e.value.sumOf { it.n } }.thenByDescending { it.key })!!.key
        else -> held.maxBy { it.at }.value
    }
}

/** The shorter of two sample intervals, either 0 for unknown. */
internal fun shorterInterval(a: Long, b: Long): Long = when {
    a <= 0 -> b
    b <= 0 -> a
    else -> minOf(a, b)
}
