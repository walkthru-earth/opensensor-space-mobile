package space.opensensor.data.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.opensensor.model.Basis

/**
 * The newest UTC day with valid readings of one thing's main metric, as My devices shows it. [mean]
 * is the day tier aggregate in the metric's stored unit, [coverage] the share of expected rows, null
 * when the interval is unknown. The mean shows with the metric's decimals.
 */
data class LatestDay(
    val startEpochS: Long,
    val mean: Float,
    val coverage: Float?,
    val metric: MetricSpec,
)

/** Picks [LatestDay] from the day tiers, the same rule on both phones. */
object LatestDays {
    private const val DAY_S = 86_400L

    /**
     * The metrics whose day can stand for a thing that has data for [available], best first. That is
     * the metric History opens on ([Metrics.defaultOf]) and, when it is a corrected view, the stored
     * column it corrects, so a day without the corrected value still shows the raw one (#92).
     */
    fun candidatesOf(available: List<MetricSpec>): List<MetricSpec> {
        val main = Metrics.defaultOf(available) ?: return emptyList()
        if (!main.derived) return listOf(main)
        val stored = available.firstOrNull { !it.derived && it.property == main.property && it.basis == Basis.RAW }
        return listOfNotNull(main, stored)
    }

    /**
     * The latest day of one thing from its day [buckets] by metric id, among [metrics]. The newest day with valid rows
     * and a finite mean of a [candidatesOf] metric wins, and on the same day the earlier candidate wins. Null when no
     * candidate has a valid day.
     */
    fun latest(buckets: Map<String, List<TierBucket>>, metrics: List<MetricSpec>): LatestDay? {
        // Candidates come from the metrics with valid rows, as [observe] picks them, and a day still
        // needs a finite mean to show. So a NaN day of the main metric falls back to its stored column.
        val available = metrics.filter { m -> buckets[m.id].orEmpty().any { it.validRows > 0 } }
        var best: LatestDay? = null
        for (metric in candidatesOf(available)) {
            val day = buckets[metric.id].orEmpty().filter(::shown).maxByOrNull { it.startEpochS } ?: continue
            if (best != null && day.startEpochS <= best.startEpochS) continue
            val expected = Resolution.DAY.expectedRows(day.sampleIntervalMs)
            val coverage = if (expected > 0) (day.validRows.toFloat() / expected).coerceIn(0f, 1f) else null
            best = LatestDay(day.startEpochS, day.mean, coverage, metric)
        }
        return best
    }

    /** A day with valid rows and a finite mean. A NaN or infinite mean is no reading. */
    private fun shown(bucket: TierBucket): Boolean = bucket.validRows > 0 && bucket.mean.isFinite()

    /**
     * [latest] of one [thing] from [tiers], reading the day buckets of the candidates over the
     * [lookbackDays] up to the thing's last day, so a phone that builds tiers on demand decodes only
     * those days. [metrics] is the catalog to pick from ([MetricCatalog.union]), read again as it grows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(tiers: HistoryTiers, thing: String, metrics: Flow<List<MetricSpec>>, lookbackDays: Int = 7): Flow<LatestDay?> =
        combine(tiers.observeMetricsWithData(thing), tiers.observeDays(thing), metrics) { ids, span, known ->
            candidatesOf(known.filter { it.id in ids }) to span.lastDayS
        }.distinctUntilChanged().flatMapLatest { (candidates, lastDayS) ->
            if (candidates.isEmpty() || lastDayS == null) {
                flowOf(null)
            } else {
                val from = lastDayS - (lookbackDays - 1) * DAY_S
                combine(candidates.map { m -> tiers.observe(thing, m.id, Resolution.DAY, from, lastDayS + DAY_S).map { m.id to it } }) { pairs ->
                    latest(pairs.toMap(), candidates)
                }
            }
        }.distinctUntilChanged()
}
