package earth.walkthru.aqsync.data.history

import kotlinx.coroutines.flow.Flow

/**
 * One metric's aggregate in one UTC tier bucket, the plain form of Android's `metric_bucket` row.
 * [rows] counts every timed row in the slot, [validRows] those that passed the metric's gate.
 */
data class TierBucket(
    val startEpochS: Long,
    val rows: Int,
    val validRows: Int,
    val estimatedRows: Int,
    val mean: Float,
    val min: Float,
    val max: Float,
)

/** The UTC days that hold derived data for one station: how many, the first and the last (epoch seconds of UTC midnight). */
data class DaySpan(val count: Int = 0, val firstDayS: Long? = null, val lastDayS: Long? = null)

/**
 * The aggregate tiers (15 min, hour, day) History reads for Week, Month and Year. Android serves them
 * from Room (`metric_bucket`, built by `HistoryDeriver`). iOS computes them from the archive in
 * memory ([ArchiveHistoryTiers]). Both are pure caches of the verified files.
 */
interface HistoryTiers {
    /** Buckets of [tier] for [metricId] whose start is in `[fromS, toS)`, oldest first. Re-emits when the tiers change. */
    fun observe(station: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>>

    /** Days with any day-tier bucket for [station]. */
    fun observeDays(station: String): Flow<DaySpan>

    /** Metric ids with at least one valid row in a day-tier bucket of [station]. */
    fun observeMetricsWithData(station: String): Flow<Set<String>>
}

/** The tier rows of one aggregated [Bucket], one per metric that had values. */
fun Bucket.tierBuckets(): Map<String, TierBucket> = metrics.mapValues { (_, a) ->
    TierBucket(startEpochS, rows, a.n, estimatedRows, a.mean, a.min, a.max)
}
