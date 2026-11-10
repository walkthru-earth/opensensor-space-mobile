package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * One tier [bucket] of a metric with what the stored row checks found in it (#91). [flaggedRows] of
 * its valid rows were flagged, with the OR of their [flags], and [cleanMean] is the mean of the others,
 * null when none passed. A bucket with no flagged row keeps 0, 0 and null.
 */
data class CheckedBucket(val bucket: TierBucket, val flaggedRows: Int = 0, val flags: Int = 0, val cleanMean: Float? = null)

/** Reads a metric's tier buckets with their [RowChecks.FLAGS_ID] and [RowChecks.cleanId] companions, the same on both phones. */
object CheckedTiers {
    /** The [tier] buckets of [metric], each with its checks. Only a [MetricSpec.cleanable] metric reads the companions. */
    fun observe(tiers: HistoryTiers, thing: String, metric: MetricSpec, tier: Resolution, fromS: Long, toS: Long): Flow<List<CheckedBucket>> {
        val values = tiers.observe(thing, metric.id, tier, fromS, toS)
        if (!metric.cleanable) return values.map { list -> list.map(::CheckedBucket) }
        return combine(
            values,
            tiers.observe(thing, RowChecks.FLAGS_ID, tier, fromS, toS),
            tiers.observe(thing, RowChecks.cleanId(metric.id), tier, fromS, toS),
            ::join,
        )
    }

    /** Joins each of [values] with the [flags] and [clean] companions of the same start. */
    fun join(values: List<TierBucket>, flags: List<TierBucket>, clean: List<TierBucket>): List<CheckedBucket> {
        if (clean.isEmpty()) return values.map(::CheckedBucket)
        val maskAt = flags.associate { it.startEpochS to it.mean.toInt() }
        val cleanAt = clean.associateBy { it.startEpochS }
        return values.map { b ->
            val c = cleanAt[b.startEpochS]
            val flagged = if (c == null) 0 else (b.validRows - c.validRows).coerceAtLeast(0)
            if (c == null || flagged == 0) {
                CheckedBucket(b)
            } else {
                CheckedBucket(b, flagged, maskAt[b.startEpochS] ?: 0, c.mean.takeIf { c.validRows > 0 && it.isFinite() })
            }
        }
    }
}
