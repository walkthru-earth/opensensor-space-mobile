package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ClockAnchorStore
import earth.walkthru.aqsync.archive.EmptyClockAnchorStore
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetReader
import earth.walkthru.aqsync.data.history.parquet.PrimitiveColumn
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import earth.walkthru.aqsync.data.history.parquet.ScanRequest
import kotlinx.coroutines.CancellationException
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
 * The derived daily file of a station's UTC day (#63), for the Day view. The platform hands it out
 * only when it was built from exactly [sources] (its source signature matches), so its rows are the
 * rows of those files. Null makes the reader decode the raw files of the day.
 */
fun interface DerivedDayFiles {
    /** The day file, which the caller closes, or null when there is none or it is stale. */
    suspend fun open(station: String, epochDay: Long, sources: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>): RangeSource?

    companion object {
        /** No derived layer, every day reads its raw files. */
        val NONE: DerivedDayFiles = DerivedDayFiles { _, _, _, _ -> null }
    }
}

/**
 * Serves the History chart on both phones: Week, Month and Year from the [tiers], Day from the
 * verified rows. Each UTC day of a Day window reads its [derived] daily file when one is current,
 * with the UTC range pruning row groups and pages, and the raw files otherwise (today, while files
 * still arrive). Reactive, a new synced, indexed or re-dated file re-emits. Pure reads, nothing
 * here writes to the archive or the index.
 */
class HistorySeriesReader(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val tiers: HistoryTiers,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val clockAnchors: ClockAnchorStore = EmptyClockAnchorStore,
    private val derived: DerivedDayFiles = DerivedDayFiles.NONE,
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
        val rows = Rows()
        if (toS > fromS) {
            val fromNs = fromS * NS
            val toNs = toS * NS
            val firstDay = StationDays.dayOf(fromNs)
            val lastDay = StationDays.dayOf(toNs - 1)
            // Whole UTC days, so each day's source set is the one its derived file was built from.
            val files = index.timeline(station, firstDay * StationDays.DAY_NS, (lastDay + 1) * StationDays.DAY_NS)
            val anchors = if (files.any { it.hasEstimatedUtc }) TimeEstimate.anchors(index.indexedForStation(station).map { it.info() }, clockAnchors.anchors(station)) else emptyMap()
            val seen = RowKeys()
            val byDay = StationDays.sourcesByDay(files) { it }
            for (day in firstDay..lastDay) {
                val sources = byDay[day] ?: continue
                val dayFrom = maxOf(fromNs, day * StationDays.DAY_NS)
                val dayTo = minOf(toNs, (day + 1) * StationDays.DAY_NS)
                if (readDerived(station, day, sources, anchors, metric, dayFrom, dayTo, seen, rows)) continue
                for (f in sources) if (f.onTimeline(dayFrom, dayTo)) readRaw(f, anchors, metric, dayFrom, dayTo, seen, rows)
            }
        }
        // Files come ordered by first UTC and rows inside are ordered, a FLUSH file can still interleave.
        val order = IntArray(rows.size) { it }
        val times = rows.times
        DailyParquetBuilder.sortByTimeline(order, times)
        val values = rows.values
        val estimatedFlags = rows.estimated
        val binned = order.size > RAW_POINT_CAP
        val points = ArrayList<HistoryPoint>(if (binned) 1_440 else order.size)
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
        return HistorySeries(metric, range, fromS, toS, points, validRows = n, expectedRows = (toS - fromS) / 10, mean = if (n > 0) (sum / n).toFloat() else null, max = max, binned = binned, estimatedRows = rows.estimatedValid)
    }

    /** The rows of one raw file inside `[fromNs, toNs)`, timed by UTC or by the boot's anchor. */
    private fun readRaw(f: ArchiveRecord, anchors: Map<String, TimeEstimate.Anchor>, metric: MetricSpec, fromNs: Long, toNs: Long, seen: RowKeys, rows: Rows) {
        open(f)?.use { pf ->
            val md = pf.metadata
            fun long(name: String) = md.leaf(name)?.let { LongColumn.of(pf.readColumn(it)) }
            val utc = long(ParquetIndexer.UTC) ?: return@use
            val bootHi = long("boot_id_hi")
            val bootLo = long("boot_id_lo")
            val seq = long(ParquetIndexer.SEQ)
            val anchor = if (f.hasEstimatedUtc) anchors[f.bootId]?.takeIf { it.boot == md.bootId } else null
            val mono = if (anchor != null) long(ParquetIndexer.MONO) else null
            val gate = metric.gate?.let { g -> long(g.column) }
            val col = md.leaf(metric.column)?.let { DoubleColumn.of(pf.readColumn(it)) }
            for (i in 0 until utc.size) {
                var est = false
                val ns = if (utc.has(i)) {
                    utc.values[i]
                } else {
                    if (mono == null || !mono.has(i)) continue
                    est = true
                    anchor?.estimateNsOrNull(mono.values[i]) ?: continue
                }
                if (ns < fromNs || ns >= toNs) continue
                if (!seen.firstSight(bootHi.at(i), bootLo.at(i), seq != null && seq.has(i), if (seq != null && seq.has(i)) seq.values[i] else ns)) continue
                val ok = metric.accepts(if (gate != null && gate.has(i)) gate.values[i] else null)
                val v = if (ok && col != null && col.has(i)) col.values[i].toFloat().takeIf { it.isFinite() } else null
                rows.add(ns.floorDiv(NS), v ?: Float.NaN, est)
            }
        }
    }

    /**
     * The rows of one UTC day's derived file inside `[fromNs, toNs)`. False when the day has no current
     * derived file or it fails to read, and the caller reads the raw files instead.
     */
    private suspend fun readDerived(
        station: String,
        day: Long,
        sources: List<ArchiveRecord>,
        anchors: Map<String, TimeEstimate.Anchor>,
        metric: MetricSpec,
        fromNs: Long,
        toNs: Long,
        seen: RowKeys,
        rows: Rows,
    ): Boolean {
        val mark = rows.size
        val marked = rows.estimatedValid
        return try {
            val source = derived.open(station, day, sources, anchors) ?: return false
            ParquetReader.open(source).use { reader ->
                val names = reader.leaves.mapTo(HashSet()) { it.name }
                val estimated = DailyParquetBuilder.ESTIMATED_UTC in names
                val wanted = listOfNotNull(ParquetIndexer.UTC, DailyParquetBuilder.ESTIMATED_UTC, "boot_id_hi", "boot_id_lo", ParquetIndexer.SEQ, metric.gate?.column, metric.column).filter { it in names }
                // Rows dated by an estimate have a null UTC, so only a file without them can prune by UTC.
                val batch = reader.scan(
                    if (estimated) ScanRequest(wanted) else ScanRequest(wanted, filterColumn = ParquetIndexer.UTC, filterRanges = listOf(fromNs until toNs)),
                )
                val utc = batch[ParquetIndexer.UTC] ?: return@use false
                val est = batch[DailyParquetBuilder.ESTIMATED_UTC]
                val bootHi = batch["boot_id_hi"]
                val bootLo = batch["boot_id_lo"]
                val seq = batch[ParquetIndexer.SEQ]
                val gate = metric.gate?.let { batch[it.column] }
                val col = batch[metric.column]
                for (i in 0 until batch.rowCount) {
                    val real = !utc.isNull(i)
                    val ns = if (real) utc.long(i) else if (est != null && !est.isNull(i)) est.long(i) else continue
                    if (ns < fromNs || ns >= toNs) continue
                    val bySeq = seq != null && !seq.isNull(i)
                    if (!seen.firstSight(bootHi.longOrZero(i), bootLo.longOrZero(i), bySeq, if (bySeq) seq!!.long(i) else ns)) continue
                    val ok = metric.accepts(gate?.asLong(i))
                    val v = if (ok) col?.asDouble(i)?.toFloat()?.takeIf { it.isFinite() } else null
                    rows.add(ns.floorDiv(NS), v ?: Float.NaN, !real)
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A derived file is a cache. One that fails to read gives way to the raw files, its rows go.
            rows.truncate(mark, marked)
            false
        }
    }

    /** The verified copy, or null when it is gone or the reader refuses it (History then skips it). */
    private fun open(record: ArchiveRecord): ParquetFile? = try {
        ParquetFile.open(store.reader(record.key), record.name)
    } catch (e: Exception) {
        null
    }

    /** The non-null long at [i] of an integer column. */
    private fun PrimitiveColumn.long(i: Int): Long = when (this) {
        is PrimitiveColumn.I64 -> values[i]
        is PrimitiveColumn.I32 -> values[i].toLong()
        else -> asLong(i) ?: 0L
    }

    /** The [RowKey] rule, a missing column or cell counts as 0. */
    private fun PrimitiveColumn?.longOrZero(i: Int): Long = if (this == null || isNull(i)) 0L else long(i)

    /** Day view rows in primitive arrays: second, value (NaN for invalid or null) and estimate flag. */
    private class Rows {
        var times = LongArray(1_024)
        var values = FloatArray(1_024)
        var estimated = BooleanArray(1_024)
        var size = 0

        /** Estimated rows that hold a value. */
        var estimatedValid = 0L

        fun add(second: Long, value: Float, isEstimate: Boolean) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                values = values.copyOf(size * 2)
                estimated = estimated.copyOf(size * 2)
            }
            times[size] = second
            values[size] = value
            estimated[size] = isEstimate
            size++
            if (isEstimate && !value.isNaN()) estimatedValid++
        }

        fun truncate(size: Int, estimatedValid: Long) {
            this.size = size
            this.estimatedValid = estimatedValid
        }
    }

    companion object {
        /** Above this many rows the day view bins to minutes. A full day is 8,640 rows, so a full day always bins. */
        const val RAW_POINT_CAP = 3_000
        private const val NS = 1_000_000_000L
    }
}
