package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.local.database.AggregateBucket
import earth.walkthru.aqsync.data.local.database.AggregateBucketDao
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Which tier column carries a metric's mean / min / max (the wide tier table is replaced by a per-metric one in the registry step). */
private fun HistoryMetric.mean(b: AggregateBucket): Float? = when (this) {
    HistoryMetric.PM25 -> b.pm25Mean
    HistoryMetric.PM10 -> b.pm10Mean
    HistoryMetric.PM1 -> b.pm1Mean
    HistoryMetric.BOARD_TEMP -> b.boardTempMean
}
private fun HistoryMetric.min(b: AggregateBucket): Float? = if (this == HistoryMetric.PM25) b.pm25Min?.toFloat() else null
private fun HistoryMetric.max(b: AggregateBucket): Float? = if (this == HistoryMetric.PM25) b.pm25Max?.toFloat() else null

/**
 * Serves the History screen from the tiers (week / month / year) or the rows (day). Reactive: a
 * derive pass or a new synced file re-emits. Pure reads; nothing here writes.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
    private val bucketDao: AggregateBucketDao,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(station: String, metric: HistoryMetric, range: HistoryRange, toS: Long): Flow<HistorySeries> {
        val fromS = toS - range.seconds
        val tier = range.tier
        return if (tier != null) {
            bucketDao.observe(station, tier.seconds, tier.bucketStart(fromS), toS).map { buckets -> fromBuckets(metric, range, fromS, toS, tier, buckets) }
        } else {
            // Re-read the rows whenever the station's file list changes (a sync, a back-fill).
            archivedFileDao.observeForStation(station).map { files -> files.filter { it.indexed }.size to files.sumOf { it.rows ?: 0L } }
                .flatMapLatest { flow { emit(fromRows(station, metric, range, fromS, toS)) }.flowOn(Dispatchers.IO) }
        }
    }

    private fun fromBuckets(metric: HistoryMetric, range: HistoryRange, fromS: Long, toS: Long, tier: Resolution, buckets: List<AggregateBucket>): HistorySeries {
        val points = buckets.map { b ->
            val valid = if (metric.particle) b.validRows else b.rows
            HistoryPoint(b.startEpochS, b.startEpochS + tier.seconds, metric.mean(b), metric.min(b), metric.max(b), (valid.toFloat() / tier.expectedRows).coerceIn(0f, 1f), estimated = b.estimatedRows * 2 >= b.rows && b.estimatedRows > 0)
        }
        var weighted = 0.0
        var weight = 0L
        var max: Float? = null
        for (b in buckets) {
            val m = metric.mean(b) ?: continue
            val n = if (metric.particle) b.validRows else b.rows
            weighted += m.toDouble() * n
            weight += n
            val hi = metric.max(b) ?: m
            max = if (max == null || hi > max) hi else max
        }
        return HistorySeries(
            metric, range, fromS, toS, points,
            validRows = weight,
            expectedRows = range.seconds / 10,
            mean = if (weight > 0) (weighted / weight).toFloat() else null,
            max = max,
            binned = false,
            estimatedRows = buckets.sumOf { it.estimatedRows.toLong() },
        )
    }

    private suspend fun fromRows(station: String, metric: HistoryMetric, range: HistoryRange, fromS: Long, toS: Long): HistorySeries {
        val files = archivedFileDao.withUtcBetween(station, fromS * 1_000_000_000L, toS * 1_000_000_000L)
        val anchors = if (files.any { it.hasEstimatedUtc }) TimeEstimate.anchors(archivedFileDao.indexedForStation(station).map { it.info() }) else emptyMap()
        val times = ArrayList<Long>()
        val values = ArrayList<Float>() // NaN = invalid / null
        val estimatedFlags = ArrayList<Boolean>()
        var estimatedRows = 0L
        for (f in files) {
            val file = archive.fileFor(f.station, f.name)
            if (!file.isFile) continue
            val pf = try { ParquetFile.open(file) } catch (e: ParquetFormatException) { continue }
            val md = pf.metadata
            val utc = md.leaf(ParquetIndexer.UTC)?.let { pf.readColumn(it) } ?: continue
            val anchor = if (f.hasEstimatedUtc) anchors[f.bootId]?.takeIf { it.boot == md.bootId } else null
            val mono = if (anchor != null) md.leaf(ParquetIndexer.MONO)?.let { pf.readColumn(it) } else null
            val pms = md.leaf(ParquetIndexer.PMS_STATUS)?.let { pf.readColumn(it) }
            val col = md.leaf(metric.column)?.let { pf.readColumn(it) }
            for (i in 0 until utc.size) {
                var est = false
                val ns = utc.asLong(i) ?: run {
                    val m = mono?.asLong(i) ?: return@run null
                    est = true
                    anchor?.estimateNs(m)
                } ?: continue
                val s = Math.floorDiv(ns, 1_000_000_000L)
                if (s < fromS || s >= toS) continue
                val ok = !metric.particle || pms?.asLong(i) == ParquetIndexer.PMS_VALID.toLong()
                val v = if (ok) col?.asDouble(i)?.toFloat() else null
                times += s
                values += (v ?: Float.NaN)
                estimatedFlags += est
                if (est && v != null) estimatedRows++
            }
        }
        // Files come ordered by first UTC, rows inside are ordered; a FLUSH file can still interleave.
        val order = times.indices.sortedBy { times[it] }
        val binned = order.size > RAW_POINT_CAP
        val points = ArrayList<HistoryPoint>()
        var sum = 0.0
        var n = 0L
        var max: Float? = null
        if (!binned) {
            for (i in order) {
                val v = values[i]
                val valid = !v.isNaN()
                points += HistoryPoint(times[i], times[i] + 10, if (valid) v else null, null, null, if (valid) 1f else 0f, estimated = estimatedFlags[i])
                if (valid) { sum += v; n++; if (max == null || v > max) max = v }
            }
        } else {
            var bucket = Long.MIN_VALUE
            var bSum = 0.0
            var bN = 0
            var bEst = 0
            var bAll = 0
            var bMin = Float.MAX_VALUE
            var bMax = -Float.MAX_VALUE
            fun flush() {
                if (bucket != Long.MIN_VALUE) points += HistoryPoint(bucket, bucket + 60, if (bN > 0) (bSum / bN).toFloat() else null, if (bN > 0) bMin else null, if (bN > 0) bMax else null, bN / 6f, estimated = bEst * 2 >= bAll && bEst > 0)
            }
            for (i in order) {
                val b = Math.floorDiv(times[i], 60L) * 60
                if (b != bucket) { flush(); bucket = b; bSum = 0.0; bN = 0; bEst = 0; bAll = 0; bMin = Float.MAX_VALUE; bMax = -Float.MAX_VALUE }
                bAll++
                if (estimatedFlags[i]) bEst++
                val v = values[i]
                if (!v.isNaN()) {
                    bSum += v; bN++
                    if (v < bMin) bMin = v
                    if (v > bMax) bMax = v
                    sum += v; n++
                    if (max == null || v > max) max = v
                }
            }
            flush()
        }
        return HistorySeries(metric, range, fromS, toS, points, validRows = n, expectedRows = range.seconds / 10, mean = if (n > 0) (sum / n).toFloat() else null, max = max, binned = binned, estimatedRows = estimatedRows)
    }

    companion object {
        /** Above this many rows the day view bins to minutes; a full day is 8,640 rows, so a day always bins. */
        const val RAW_POINT_CAP = 3_000
    }
}
