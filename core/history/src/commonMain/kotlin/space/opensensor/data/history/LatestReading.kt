package space.opensensor.data.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The newest 15 minute reading of one thing's main metric on this phone, as the Discover own pin
 * shows it (#273). [endEpochS] is the end of its 15 minute bucket, [mean] the bucket mean in the
 * metric's stored unit. [displayStep] is the profile's step for the metric's column. It is read from
 * the history tiers, so it works with no network and never comes from a live link. [flaggedRows] of the
 * bucket's rows failed a stored row check (#91), with the OR of their [flags], and [cleanMean] is the
 * mean of the others, null when none passed.
 */
data class LatestReading(
    val endEpochS: Long,
    val mean: Float,
    val metric: MetricSpec,
    val displayStep: Double,
    val flaggedRows: Int = 0,
    val flags: Int = 0,
    val cleanMean: Float? = null,
) {
    /** The mean over the rows that passed every check, which Simple shows, null when none passed. */
    val passedMean: Float? get() = if (flaggedRows == 0) mean else cleanMean
}

/** Picks [LatestReading] from the 15 minute tiers, the same rule on both phones. */
object LatestReadings {
    private const val DAY_S = 86_400L

    /**
     * The newest reading of one thing from its 15 minute [buckets] by metric id. The candidates are
     * [LatestDays.candidatesOf] the metrics with valid rows. The newest bucket with valid rows and a
     * finite mean wins, and at the same time the earlier candidate wins. Null when no candidate has one.
     */
    fun latest(
        buckets: Map<String, List<TierBucket>>,
        metrics: List<MetricSpec> = Metrics.all,
        stepOf: (MetricSpec) -> Double = { LatestDays.displayStepOf(it) },
    ): LatestReading? = latestChecked(buckets.mapValues { (_, list) -> list.map(::CheckedBucket) }, metrics, stepOf)

    /** [latest] of buckets that carry their stored checks ([CheckedTiers]). */
    fun latestChecked(
        buckets: Map<String, List<CheckedBucket>>,
        metrics: List<MetricSpec> = Metrics.all,
        stepOf: (MetricSpec) -> Double = { LatestDays.displayStepOf(it) },
    ): LatestReading? {
        val available = metrics.filter { m -> buckets[m.id].orEmpty().any { it.bucket.validRows > 0 } }
        var best: LatestReading? = null
        for (metric in LatestDays.candidatesOf(available)) {
            val c = buckets[metric.id].orEmpty().filter { shown(it.bucket) }.maxByOrNull { it.bucket.startEpochS } ?: continue
            val end = c.bucket.startEpochS + Resolution.MIN15.seconds
            if (best != null && end <= best.endEpochS) continue
            best = LatestReading(end, c.bucket.mean, metric, stepOf(metric), c.flaggedRows, c.flags, c.cleanMean)
        }
        return best
    }

    /** A bucket with valid rows and a finite mean. A NaN or infinite mean is no reading. */
    private fun shown(bucket: TierBucket): Boolean = bucket.validRows > 0 && bucket.mean.isFinite()

    /**
     * [latest] of one [thing] from [tiers], reading the 15 minute buckets of the candidates over the
     * thing's last two days, so a phone that builds tiers on demand decodes only those days. When those
     * days hold no candidate reading, as when the main metric fails its gate while other columns still
     * record, the day tier finds the newest older day with one and its quarters are read instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(tiers: HistoryTiers, thing: String, metrics: List<MetricSpec> = Metrics.all): Flow<LatestReading?> =
        combine(tiers.observeMetricsWithData(thing), tiers.observeDays(thing)) { ids, span ->
            LatestDays.candidatesOf(metrics.filter { it.id in ids }) to span
        }.distinctUntilChanged().flatMapLatest { (candidates, span) ->
            val lastDayS = span.lastDayS
            val firstDayS = span.firstDayS ?: lastDayS
            if (candidates.isEmpty() || lastDayS == null || firstDayS == null) {
                flowOf(null)
            } else {
                val from = lastDayS - DAY_S
                quarters(tiers, thing, candidates, from, lastDayS + DAY_S).flatMapLatest { recent ->
                    if (recent != null || firstDayS >= from) {
                        flowOf(recent)
                    } else {
                        newestDay(tiers, thing, candidates, firstDayS, from).flatMapLatest { dayS ->
                            if (dayS == null) flowOf(null) else quarters(tiers, thing, candidates, dayS, dayS + DAY_S)
                        }
                    }
                }
            }
        }.distinctUntilChanged()

    private fun quarters(tiers: HistoryTiers, thing: String, candidates: List<MetricSpec>, fromS: Long, toS: Long): Flow<LatestReading?> =
        combine(candidates.map { m -> CheckedTiers.observe(tiers, thing, m, Resolution.MIN15, fromS, toS).map { m.id to it } }) { pairs ->
            latestChecked(pairs.toMap(), candidates)
        }

    /** The start of the newest day in [fromS, toS) where a candidate has a shown day bucket, or null. */
    private fun newestDay(tiers: HistoryTiers, thing: String, candidates: List<MetricSpec>, fromS: Long, toS: Long): Flow<Long?> =
        combine(candidates.map { m -> tiers.observe(thing, m.id, Resolution.DAY, fromS, toS).map { m.id to it } }) { pairs ->
            LatestDays.latest(pairs.toMap(), candidates) { 0.0 }?.startEpochS
        }.distinctUntilChanged()
}
