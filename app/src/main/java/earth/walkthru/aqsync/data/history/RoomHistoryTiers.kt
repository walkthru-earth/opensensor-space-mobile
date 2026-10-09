package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The shared [HistoryTiers] over Room's `metric_bucket`, which [HistoryDeriver] keeps in step with the archive. */
@Singleton
class RoomHistoryTiers @Inject constructor(private val bucketDao: MetricBucketDao) : HistoryTiers {
    override fun observe(station: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> =
        bucketDao.observe(station, metricId, tier.seconds, fromS, toS).map { rows ->
            rows.map { TierBucket(it.startEpochS, it.rows, it.validRows, it.estimatedRows, it.mean, it.min, it.max) }
        }

    override fun observeDays(station: String): Flow<DaySpan> =
        combine(bucketDao.observeDayCount(station), bucketDao.observeFirstDay(station), bucketDao.observeLastDay(station)) { n, first, last ->
            DaySpan(n, first, last)
        }.distinctUntilChanged()

    override fun observeMetricsWithData(station: String): Flow<Set<String>> {
        val chartable = Metrics.chartable
        if (chartable.isEmpty()) return flowOf(emptySet())
        return combine(
            chartable.map { spec ->
                bucketDao.observeAllDays(spec.id).map { rows -> spec.id.takeIf { rows.any { it.station == station && it.validRows > 0 } } }
            },
        ) { ids -> ids.filterNotNull().toSet() }.distinctUntilChanged()
    }
}
