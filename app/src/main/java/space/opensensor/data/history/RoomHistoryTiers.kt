package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.opensensor.data.local.database.MetricBucketDao
import javax.inject.Inject
import javax.inject.Singleton

/** The shared [HistoryTiers] over Room's `metric_bucket`, which [HistoryDeriver] keeps in step with the archive. */
@Singleton
class RoomHistoryTiers @Inject constructor(private val bucketDao: MetricBucketDao) : HistoryTiers {
    override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> =
        bucketDao.observe(thing, metricId, tier.seconds, fromS, toS).map { rows ->
            rows.map { TierBucket(it.startEpochS, it.rows, it.validRows, it.estimatedRows, it.mean, it.min, it.max, it.sampleIntervalMs) }
        }

    override fun observeDays(thing: String): Flow<DaySpan> =
        combine(bucketDao.observeDayCount(thing), bucketDao.observeFirstDay(thing), bucketDao.observeLastDay(thing)) { n, first, last ->
            DaySpan(n, first, last)
        }.distinctUntilChanged()

    override fun observeMetricsWithData(thing: String): Flow<Set<String>> {
        val metrics = Metrics.all
        if (metrics.isEmpty()) return flowOf(emptySet())
        return combine(
            metrics.map { spec ->
                bucketDao.observeAllDays(spec.id).map { rows -> spec.id.takeIf { rows.any { it.thing == thing && it.validRows > 0 } } }
            },
        ) { ids -> ids.filterNotNull().toSet() }.distinctUntilChanged()
    }
}
