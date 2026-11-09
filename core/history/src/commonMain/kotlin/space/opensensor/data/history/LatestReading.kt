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
 * the history tiers, so it works with no network and never comes from a live link.
 */
data class LatestReading(
    val endEpochS: Long,
    val mean: Float,
    val metric: MetricSpec,
    val displayStep: Double,
)

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
    ): LatestReading? {
        val available = metrics.filter { m -> buckets[m.id].orEmpty().any { it.validRows > 0 } }
        var best: LatestReading? = null
        for (metric in LatestDays.candidatesOf(available)) {
            val bucket = buckets[metric.id].orEmpty().filter(::shown).maxByOrNull { it.startEpochS } ?: continue
            val end = bucket.startEpochS + Resolution.MIN15.seconds
            if (best != null && end <= best.endEpochS) continue
            best = LatestReading(end, bucket.mean, metric, stepOf(metric))
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
        combine(candidates.map { m -> tiers.observe(thing, m.id, Resolution.MIN15, fromS, toS).map { m.id to it } }) { pairs ->
            latest(pairs.toMap(), candidates)
        }

    /** The start of the newest day in [fromS, toS) where a candidate has a shown day bucket, or null. */
    private fun newestDay(tiers: HistoryTiers, thing: String, candidates: List<MetricSpec>, fromS: Long, toS: Long): Flow<Long?> =
        combine(candidates.map { m -> tiers.observe(thing, m.id, Resolution.DAY, fromS, toS).map { m.id to it } }) { pairs ->
            LatestDays.latest(pairs.toMap(), candidates) { 0.0 }?.startEpochS
        }.distinctUntilChanged()
}
