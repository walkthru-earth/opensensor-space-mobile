package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ClockAnchorStore
import earth.walkthru.aqsync.archive.EmptyClockAnchorStore
import earth.walkthru.aqsync.data.history.parquet.FooterCache
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
import kotlin.time.Clock

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
 *
 * [footers] keeps the parsed footers and page indexes of derived day files, keyed by path, size and
 * modification time, so switching the metric or re-reading the same Day window parses nothing again.
 * A rebuilt day is a new file with a new identity and never matches an old entry.
 */
class HistorySeriesReader(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val tiers: HistoryTiers,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val clockAnchors: ClockAnchorStore = EmptyClockAnchorStore,
    private val derived: DerivedDayFiles = DerivedDayFiles.NONE,
    private val footers: FooterCache = FooterCache(DERIVED_FOOTERS),
    private val nowEpochS: () -> Long = { Clock.System.now().epochSeconds },
) {
    /**
     * Rows the logger could have written in `[fromS, toS)` by now. A window holding now (today, this
     * week) counts only its elapsed part, so a device that never missed a reading shows 100 %.
     */
    private fun expectedRows(fromS: Long, toS: Long): Long = (minOf(toS, nowEpochS()) - fromS).coerceAtLeast(0) / 10

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(station: String, metric: MetricSpec, range: HistoryRange, window: HistoryWindow): Flow<HistorySeries> {
        val tier = range.tier
        return if (tier != null) {
            observeTier(station, metric, range, window, tier)
        } else {
            // Re-read the rows only when the station's timeline changed (a synced or indexed file, a
            // new time estimate). A day re-read opens every file of the day.
            index.observeStation(station)
                .map { files -> files.filter { it.isIndexed }.map { Triple(it.name, it.crc32, it.timelineFirstNs) } }
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
            expectedRows = expectedRows(fromS, toS),
            mean = if (weight > 0) (weighted / weight).toFloat() else null,
            max = max,
            binned = false,
            estimatedRows = whole.sumOf { it.estimatedRows.toLong() } + listOfNotNull(leading, trailing).sumOf { it.estimatedRows },
        )
    }

    private suspend fun fromRows(station: String, metric: MetricSpec, range: HistoryRange, fromS: Long, toS: Long): HistorySeries {
        val rows = Rows()
        val indexed = if (toS > fromS) index.indexedForStation(station) else emptyList()
        val window = readWindow(fromS, toS, indexed)
        if (window != null) {
            val fromNs = window.first * NS
            val toNs = window.second * NS
            val firstDay = StationDays.dayOf(fromNs)
            val lastDay = StationDays.dayOf(toNs - 1)
            // Whole UTC days, so each day's source set is the one its derived file was built from.
            val files = index.timeline(station, firstDay * StationDays.DAY_NS, (lastDay + 1) * StationDays.DAY_NS)
            val anchors = if (files.any { it.hasEstimatedUtc }) TimeEstimate.anchors(indexed.map { it.info() }, clockAnchors.anchors(station)) else emptyMap()
            val seen = RowKeys()
            val byDay = StationDays.sourcesByDay(files) { it }
            for (day in firstDay..lastDay) {
                val sources = byDay[day] ?: continue
                val dayFrom = maxOf(fromNs, day * StationDays.DAY_NS)
                val dayTo = minOf(toNs, (day + 1) * StationDays.DAY_NS)
                if (readDerived(station, day, sources, anchors, metric, dayFrom, dayTo, seen, rows)) continue
                val daySeen = RowKeys()
                // Choose duplicates over the whole day before applying the chart window, exactly as
                // the daily builder does. A duplicate with another timestamp must not win just because
                // the builder's preferred row falls outside a fractional edge window.
                for (f in sources.sortedWith(DailyParquetBuilder.SOURCE_ORDER)) {
                    readRaw(f, anchors, metric, day * StationDays.DAY_NS, (day + 1) * StationDays.DAY_NS, dayFrom, dayTo, daySeen, seen, rows)
                }
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
        return HistorySeries(metric, range, fromS, toS, points, validRows = n, expectedRows = expectedRows(fromS, toS), mean = if (n > 0) (sum / n).toFloat() else null, max = max, binned = binned, estimatedRows = rows.estimatedValid)
    }

    /** The rows of one raw file inside `[fromNs, toNs)`, timed by UTC or by the boot's anchor. */
    private fun readRaw(f: ArchiveRecord, anchors: Map<String, TimeEstimate.Anchor>, metric: MetricSpec, dayFromNs: Long, dayToNs: Long, fromNs: Long, toNs: Long, daySeen: RowKeys, seen: RowKeys, rows: Rows) {
        open(f)?.use { pf ->
            val md = pf.metadata
            fun long(name: String) = md.leaf(name)?.let { LongColumn.of(pf.readColumn(it)) }
            val utc = UtcColumns.readEventNs(pf)?.let(LongColumn::of) ?: return@use
            val bootHi = long("boot_id_hi")
            val bootLo = long("boot_id_lo")
            val seq = long(ParquetIndexer.SEQ)
            val anchor = if (f.hasEstimatedUtc) anchors[f.bootId]?.takeIf { it.boot == md.bootId } else null
            val mono = if (anchor != null) long(ParquetIndexer.MONO) else null
            val gate = metric.gate?.let { g -> long(g.column) }
            val col = md.leaf(metric.column)?.let { DoubleColumn.of(pf.readColumn(it)) }
            val rh = metric.humidityColumn?.let { name -> md.leaf(name)?.let { DoubleColumn.of(pf.readColumn(it)) } }
            for (i in 0 until utc.size) {
                var est = false
                val ns = if (utc.has(i)) {
                    utc.values[i]
                } else {
                    if (mono == null || !mono.has(i)) continue
                    est = true
                    anchor?.estimateNsOrNull(mono.values[i]) ?: continue
                }
                if (ns < dayFromNs || ns >= dayToNs) continue
                val bySeq = seq != null && seq.has(i)
                val key = if (bySeq) seq!!.values[i] else ns
                if (!daySeen.firstSight(bootHi.at(i), bootLo.at(i), bySeq, key)) continue
                if (ns < fromNs || ns >= toNs) continue
                if (!seen.firstSight(bootHi.at(i), bootLo.at(i), bySeq, key)) continue
                val ok = metric.accepts(if (gate != null && gate.has(i)) gate.values[i] else null)
                val v = if (ok && col != null && col.has(i)) {
                    metric.valueOf(col.values[i], if (rh != null && rh.has(i)) rh.values[i] else null)?.toFloat()?.takeIf { it.isFinite() }
                } else {
                    null
                }
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
        return try {
            val source = derived.open(station, day, sources, anchors) ?: return false
            val staged = ParquetReader.open(source, footerCache = footers).use { reader ->
                val names = reader.leaves.mapTo(HashSet()) { it.name }
                val utcLeaf = reader.leaves.firstOrNull { it.name == UtcColumns.EVENT_US } ?: reader.leaves.firstOrNull { it.name == UtcColumns.EVENT_NS } ?: return@use null
                val estLeaf = reader.leaves.firstOrNull { it.name == DailyParquetBuilder.ESTIMATED_UTC }
                // Both scales come from the TIMESTAMP unit, so a file in another unit is read right or refused.
                val utcScale = UtcColumns.nanosPerUnit(utcLeaf)
                val estScale = estLeaf?.let(UtcColumns::nanosPerUnit) ?: 1L
                val wanted = listOfNotNull(utcLeaf.name, estLeaf?.name, "boot_id_hi", "boot_id_lo", ParquetIndexer.SEQ, metric.gate?.column, metric.column, metric.humidityColumn).filter { it in names }
                // Rows dated by an estimate have a null UTC, so only a file without them can prune by UTC.
                val range = UtcColumns.nsToUnit(fromNs, utcScale) until UtcColumns.nsToUnitCeil(toNs, utcScale)
                val batch = reader.scan(
                    if (estLeaf != null) ScanRequest(wanted) else ScanRequest(wanted, filterColumn = utcLeaf.name, filterRanges = listOf(range)),
                )
                val utc = batch[utcLeaf.name] ?: return@use null
                val est = estLeaf?.let { batch[it.name] }
                val bootHi = batch["boot_id_hi"]
                val bootLo = batch["boot_id_lo"]
                val seq = batch[ParquetIndexer.SEQ]
                val gate = metric.gate?.let { batch[it.column] }
                val col = batch[metric.column]
                val rh = metric.humidityColumn?.let { batch[it] }
                val staged = DerivedRows(batch.rowCount)
                for (i in 0 until batch.rowCount) {
                    val real = !utc.isNull(i)
                    val ns = if (real) {
                        UtcColumns.scaleToNs(utc.long(i), utcScale) ?: continue
                    } else if (est != null && !est.isNull(i)) {
                        UtcColumns.scaleToNs(est.long(i), estScale) ?: continue
                    } else {
                        continue
                    }
                    if (ns < fromNs || ns >= toNs) continue
                    val bySeq = seq != null && !seq.isNull(i)
                    val ok = metric.accepts(gate?.asLong(i))
                    val v = if (ok) col?.asDouble(i)?.let { metric.valueOf(it, rh?.asDouble(i)) }?.toFloat()?.takeIf { it.isFinite() } else null
                    val at = staged.rows.size
                    staged.hi[at] = bootHi.longOrZero(i)
                    staged.lo[at] = bootLo.longOrZero(i)
                    staged.bySequence[at] = bySeq
                    staged.key[at] = if (bySeq) seq!!.long(i) else ns
                    staged.rows.add(ns.floorDiv(NS), v ?: Float.NaN, !real)
                }
                staged
            } ?: return false
            // Commit only after every row was extracted and the source closed. A cache failure must
            // leave neither points nor row keys behind, otherwise the raw fallback loses those rows.
            for (i in 0 until staged.rows.size) {
                if (!seen.firstSight(staged.hi[i], staged.lo[i], staged.bySequence[i], staged.key[i])) continue
                rows.add(staged.rows.times[i], staged.rows.values[i], staged.rows.estimated[i])
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A derived file is a cache. One that fails to read gives way to the raw files.
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

    /** A derived read staged until both extraction and closing succeeded. */
    private class DerivedRows(capacity: Int) {
        val rows = Rows()
        val hi = LongArray(capacity)
        val lo = LongArray(capacity)
        val key = LongArray(capacity)
        val bySequence = BooleanArray(capacity)
    }

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
    }

    companion object {
        /** Above this many rows the day view bins to minutes. A full day is 8,640 rows, so a full day always bins. */
        const val RAW_POINT_CAP = 3_000

        /** Derived day footers kept, a month of days. Each is a few tens of KB with its page index. */
        const val DERIVED_FOOTERS = 32
        private const val NS = 1_000_000_000L
        private const val DAY_S = 86_400L

        /**
         * Seconds whose nanoseconds, and the nanoseconds of the day after them, still fit a Long.
         * About the years 1678 to 2262.
         */
        internal const val MAX_WINDOW_S = Long.MAX_VALUE / NS - 2 * DAY_S

        /**
         * `[fromS, toS)` clamped to the time span of [indexed] plus one day on each side, and to
         * [MAX_WINDOW_S], as the seconds `first until second`. Null when nothing is left to read. A window
         * reaching far past the archive (a date picker, a corrupt timestamp) never multiplies into a
         * wrapped nanosecond, and costs no lookups for days that cannot hold rows.
         */
        internal fun readWindow(fromS: Long, toS: Long, indexed: List<ArchiveRecord>): Pair<Long, Long>? {
            if (toS <= fromS) return null
            var firstNs = Long.MAX_VALUE
            var lastNs = Long.MIN_VALUE
            for (r in indexed) {
                if (!r.hasHistoryTime) continue
                val first = r.timelineFirstNs ?: continue
                val last = r.timelineLastNs ?: continue
                if (first < firstNs) firstNs = first
                if (last > lastNs) lastNs = last
            }
            if (firstNs > lastNs) return null
            val spanFrom = (firstNs.floorDiv(NS) - DAY_S).coerceAtLeast(-MAX_WINDOW_S)
            val spanTo = (lastNs.floorDiv(NS) + 1 + DAY_S).coerceAtMost(MAX_WINDOW_S)
            val from = maxOf(fromS, spanFrom)
            val to = minOf(toS, spanTo)
            return if (to > from) from to to else null
        }
    }
}
