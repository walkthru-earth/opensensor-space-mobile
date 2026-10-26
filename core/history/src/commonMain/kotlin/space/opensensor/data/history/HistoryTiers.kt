package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow

/**
 * One metric's aggregate in one UTC tier bucket, the plain form of Android's `metric_bucket` row.
 * [rows] counts every timed row in the slot, [validRows] those that passed the metric's gate. [mean]
 * is the metric's aggregate of them. [sampleIntervalMs] is the bucket's footer interval, 0 when unknown.
 */
data class TierBucket(
    val startEpochS: Long,
    val rows: Int,
    val validRows: Int,
    val estimatedRows: Int,
    val mean: Float,
    val min: Float,
    val max: Float,
    val sampleIntervalMs: Long = 0,
) {
    /** Share of the rows the interval allows in a [tier] bucket that are valid, 0 when the interval is unknown. */
    fun coverage(tier: Resolution): Float {
        val expected = tier.expectedRows(sampleIntervalMs)
        return if (expected <= 0) 0f else (validRows.toFloat() / expected).coerceIn(0f, 1f)
    }
}

/** The UTC days that hold derived data for one thing: how many, the first and the last (epoch seconds of UTC midnight). */
data class DaySpan(val count: Int = 0, val firstDayS: Long? = null, val lastDayS: Long? = null)

/**
 * The aggregate tiers (15 min, hour, day) History reads for Week, Month and Year. Android serves them
 * from Room (`metric_bucket`, built by `HistoryDeriver`). iOS computes them from the archive in
 * memory ([ArchiveHistoryTiers]). Both are pure caches of the verified files.
 */
interface HistoryTiers {
    /** Buckets of [tier] for [metricId] whose start is in `[fromS, toS)`, oldest first. Re-emits when the tiers change. */
    fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>>

    /** Days with any day-tier bucket for [thing]. */
    fun observeDays(thing: String): Flow<DaySpan>

    /** Metric ids with at least one valid row in a day-tier bucket of [thing]. */
    fun observeMetricsWithData(thing: String): Flow<Set<String>>
}

/** The tier rows of one aggregated [Bucket], one per metric that had values. */
fun Bucket.tierBuckets(): Map<String, TierBucket> = metrics.mapValues { (_, a) ->
    TierBucket(startEpochS, rows, a.n, estimatedRows, a.mean, a.min, a.max, sampleIntervalMs)
}
