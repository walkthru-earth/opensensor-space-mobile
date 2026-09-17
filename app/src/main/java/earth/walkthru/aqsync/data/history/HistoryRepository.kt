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

/** What the History chart can plot. Bands (the capybara colours) apply to PM2.5 only. */
enum class HistoryMetric(val column: String, val label: String, val unit: String, val banded: Boolean) {
    PM25("pm25_atmospheric_ug_m3", "PM2.5", "µg/m³", true),
    PM10("pm10_atmospheric_ug_m3", "PM10", "µg/m³", false),
    PM1("pm1_atmospheric_ug_m3", "PM1", "µg/m³", false),
    BOARD_TEMP("imu_temperature_c", "Board temperature", "°C", false),
    ;

    /** Whether the metric is a particle reading gated by `pms_status == 4`. */
    val particle: Boolean get() = this != BOARD_TEMP

    fun mean(b: AggregateBucket): Float? = when (this) {
        PM25 -> b.pm25Mean
        PM10 -> b.pm10Mean
        PM1 -> b.pm1Mean
        BOARD_TEMP -> b.boardTempMean
    }

    fun min(b: AggregateBucket): Float? = if (this == PM25) b.pm25Min?.toFloat() else null
    fun max(b: AggregateBucket): Float? = if (this == PM25) b.pm25Max?.toFloat() else null
}

/** Visible span and which tier serves it. `DAY` reads the rows themselves (≤ 8,640 of them). */
enum class HistoryRange(val seconds: Long, val tier: Resolution?, val label: String) {
    DAY(86_400, null, "Day"),
    WEEK(7 * 86_400, Resolution.MIN15, "Week"),
    MONTH(30 * 86_400, Resolution.HOUR, "Month"),
    YEAR(365 * 86_400, Resolution.DAY, "Year"),
}

/**
 * One plotted element: a bucket (mean with min/max envelope) or a raw 10-second row (mean only).
 * [estimated]: the time axis for this point comes from a §4.6 estimate (drawn dashed), not a UTC stamp.
 */
data class HistoryPoint(val startS: Long, val endS: Long, val mean: Float?, val min: Float?, val max: Float?, val coverage: Float, val estimated: Boolean = false)

data class HistorySeries(
    val metric: HistoryMetric,
    val range: HistoryRange,
    val fromS: Long,
    val toS: Long,
    val points: List<HistoryPoint>,
    /** Rows behind the means, and how many the logger would have produced over the whole span. */
    val validRows: Long,
    val expectedRows: Long,
    val mean: Float?,
    val max: Float?,
    /** True when the day view had to bin rows (more than [RAW_POINT_CAP]) into minutes. */
    val binned: Boolean,
    /** Rows on the estimated time axis (§4.6) among [validRows]-bearing rows. */
    val estimatedRows: Long = 0,
) {
    val hasEstimated: Boolean get() = estimatedRows > 0
    val coverage: Float get() = if (expectedRows == 0L) 0f else (validRows.toFloat() / expectedRows).coerceIn(0f, 1f)
    val isEmpty: Boolean get() = points.none { it.mean != null }
}

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
        val anchors = if (files.any { it.hasEstimatedUtc }) TimeEstimate.anchors(archivedFileDao.indexedForStation(station)) else emptyMap()
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
