package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Serves the History chart on both phones: Week, Month and Year from the [tiers], Day from the
 * verified rows. Reactive, a new synced, indexed or re-dated file re-emits. Pure reads, nothing
 * here writes to the archive or the index.
 */
class HistorySeriesReader(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val tiers: HistoryTiers,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(station: String, metric: MetricSpec, range: HistoryRange, window: HistoryWindow): Flow<HistorySeries> {
        val tier = range.tier
        return if (tier != null) {
            observeTier(station, metric, range, window, tier)
        } else {
            // Re-read the rows only when the station's timeline changed (a synced or indexed file, a
            // new time estimate). A day re-read opens every file of the day.
            index.observeStation(station)
                .map { files -> files.filter { it.indexed }.map { Triple(it.name, it.crc32, it.timelineFirstNs) } }
                .distinctUntilChanged()
                .flatMapLatest { flow { emit(fromRows(station, metric, range, window.fromS, window.toS)) }.flowOn(io) }
        }
    }

    /** Today card, the same exact local-day bounds from hourly tiers plus raw boundary rows. */
    fun observeHourlyDay(station: String, metric: MetricSpec, window: HistoryWindow): Flow<HistorySeries> =
        observeTier(station, metric, HistoryRange.DAY, window, Resolution.HOUR)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTier(station: String, metric: MetricSpec, range: HistoryRange, window: HistoryWindow, tier: Resolution): Flow<HistorySeries> =
        tiers.observe(station, metric.id, tier, tier.bucketStart(window.fromS), window.toS).mapLatest { buckets ->
            fromBuckets(station, metric, range, window.fromS, window.toS, tier, buckets)
        }.flowOn(io)

    private suspend fun fromBuckets(station: String, metric: MetricSpec, range: HistoryRange, fromS: Long, toS: Long, tier: Resolution, buckets: List<TierBucket>): HistorySeries {
        // A local midnight can cut through a UTC tier bucket (India at :30). Read only those two
        // partial edges from verified rows, whole interior buckets stay cheap.
        val firstWhole = if (tier.bucketStart(fromS) == fromS) fromS else tier.bucketStart(fromS) + tier.seconds
        val lastWholeEnd = tier.bucketStart(toS)
        val leading = if (fromS < firstWhole) fromRows(station, metric, range, fromS, minOf(firstWhole, toS)) else null
        val trailing = if (lastWholeEnd < toS && lastWholeEnd >= firstWhole) fromRows(station, metric, range, lastWholeEnd, toS) else null
        val whole = buckets.filter { it.startEpochS >= firstWhole && it.startEpochS + tier.seconds <= toS }
        val points = leading?.points.orEmpty() + whole.map { b ->
            HistoryPoint(
                b.startEpochS,
                b.startEpochS + tier.seconds,
                b.mean,
                if (metric.envelope) b.min else null,
                if (metric.envelope) b.max else null,
                (b.validRows.toFloat() / tier.expectedRows).coerceIn(0f, 1f),
                estimated = b.estimatedRows * 2 >= b.rows && b.estimatedRows > 0,
            )
        } + trailing?.points.orEmpty()
        var weighted = 0.0
        var weight = 0L
        var max: Float? = null
        for (b in whole) {
            weighted += b.mean.toDouble() * b.validRows
            weight += b.validRows
            val hi = if (metric.envelope) b.max else b.mean
            max = if (max == null || hi > max) hi else max
        }
        for (edge in listOfNotNull(leading, trailing)) {
            weighted += (edge.mean ?: 0f).toDouble() * edge.validRows
            weight += edge.validRows
            edge.max?.let { hi -> max = if (max == null || hi > max) hi else max }
        }
        return HistorySeries(
            metric, range, fromS, toS, points,
            validRows = weight,
            expectedRows = (toS - fromS) / 10,
            mean = if (weight > 0) (weighted / weight).toFloat() else null,
            max = max,
            binned = false,
            estimatedRows = whole.sumOf { it.estimatedRows.toLong() } + listOfNotNull(leading, trailing).sumOf { it.estimatedRows },
        )
    }

    private suspend fun fromRows(station: String, metric: MetricSpec, range: HistoryRange, fromS: Long, toS: Long): HistorySeries {
        val files = index.timeline(station, fromS * NS, toS * NS)
        val anchors = if (files.any { it.hasEstimatedUtc }) TimeEstimate.anchors(index.indexedForStation(station).map { it.info() }) else emptyMap()
        val times = ArrayList<Long>()
        val values = ArrayList<Float>() // NaN means invalid or null
        val estimatedFlags = ArrayList<Boolean>()
        val seen = RowKeys()
        var estimatedRows = 0L
        for (f in files) {
            val pf = open(f) ?: continue
            val md = pf.metadata
            val utc = md.leaf(ParquetIndexer.UTC)?.let { pf.readColumn(it) } ?: continue
            val bootHi = md.leaf("boot_id_hi")?.let { pf.readColumn(it) }
            val bootLo = md.leaf("boot_id_lo")?.let { pf.readColumn(it) }
            val seq = md.leaf(ParquetIndexer.SEQ)?.let { pf.readColumn(it) }
            val anchor = if (f.hasEstimatedUtc) anchors[f.bootId]?.takeIf { it.boot == md.bootId } else null
            val mono = if (anchor != null) md.leaf(ParquetIndexer.MONO)?.let { pf.readColumn(it) } else null
            val gate = metric.gate?.let { g -> md.leaf(g.column)?.let { pf.readColumn(it) } }
            val col = md.leaf(metric.column)?.let { pf.readColumn(it) }
            for (i in 0 until utc.size) {
                var est = false
                val ns = utc.asLong(i) ?: run {
                    val m = mono?.asLong(i) ?: return@run null
                    est = true
                    anchor?.estimateNs(m)
                } ?: continue
                val s = ns.floorDiv(NS)
                if (s < fromS || s >= toS) continue
                if (!seen.firstSight(bootHi?.asLong(i), bootLo?.asLong(i), seq?.asLong(i), ns)) continue
                val ok = metric.accepts(gate?.asLong(i))
                val v = if (ok) col?.asDouble(i)?.toFloat()?.takeIf { it.isFinite() } else null
                times += s
                values += (v ?: Float.NaN)
                estimatedFlags += est
                if (est && v != null) estimatedRows++
            }
        }
        // Files come ordered by first UTC and rows inside are ordered, a FLUSH file can still interleave.
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
                if (valid) {
                    sum += v
                    n++
                    if (max == null || v > max) max = v
                }
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
                if (bucket != Long.MIN_VALUE) {
                    points += HistoryPoint(
                        bucket,
                        bucket + 60,
                        if (bN > 0) (bSum / bN).toFloat() else null,
                        if (bN > 0) bMin else null,
                        if (bN > 0) bMax else null,
                        bN / 6f,
                        estimated = bEst * 2 >= bAll && bEst > 0,
                    )
                }
            }
            for (i in order) {
                val b = times[i].floorDiv(60L) * 60
                if (b != bucket) {
                    flush()
                    bucket = b
                    bSum = 0.0
                    bN = 0
                    bEst = 0
                    bAll = 0
                    bMin = Float.MAX_VALUE
                    bMax = -Float.MAX_VALUE
                }
                bAll++
                if (estimatedFlags[i]) bEst++
                val v = values[i]
                if (!v.isNaN()) {
                    bSum += v
                    bN++
                    if (v < bMin) bMin = v
                    if (v > bMax) bMax = v
                    sum += v
                    n++
                    if (max == null || v > max) max = v
                }
            }
            flush()
        }
        return HistorySeries(metric, range, fromS, toS, points, validRows = n, expectedRows = (toS - fromS) / 10, mean = if (n > 0) (sum / n).toFloat() else null, max = max, binned = binned, estimatedRows = estimatedRows)
    }

    /** The verified copy, or null when it is gone or the reader refuses it (History then skips it). */
    private fun open(record: ArchiveRecord): ParquetFile? = try {
        ParquetFile.open(store.reader(record.key), record.name)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Above this many rows the day view bins to minutes. A full day is 8,640 rows, so a full day always bins. */
        const val RAW_POINT_CAP = 3_000
        private const val NS = 1_000_000_000L
    }
}
