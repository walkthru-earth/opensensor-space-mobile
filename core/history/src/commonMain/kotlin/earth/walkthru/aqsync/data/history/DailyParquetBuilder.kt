package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlinx.coroutines.CancellationException

/**
 * One immutable source and its verified archive identity. [DailyParquetBuilder] calls [open] once to
 * time the rows and, when it writes a daily file, once more to decode the columns of the hours the
 * source fills (#98). It closes each file before it opens the next, so a day holds at most one source
 * file open (#61). [open] must give a fresh [ParquetFile] on every call.
 */
data class DailyParquetSource(val record: ArchiveRecord, val open: () -> ParquetFile)

data class DailyParquetContribution(val record: ArchiveRecord, val rowsUsed: Int, val timeAnchor: String?)

data class DailyParquetResult(
    val rows: Int,
    val columns: List<String>,
    val rowGroups: Int,
    val complete: Boolean,
    val sources: List<DailyParquetContribution>,
)

/** What one [DayPass] gives back: the day's tier buckets and, when it had a sink, the daily file. */
class DayPassResult(
    /** Buckets of every resolution that start inside the day, empty when the pass ran without tiers. */
    val buckets: List<Bucket>,
    /** The daily file written to the sink, null without a sink, without rows, or after [dailyError]. */
    val daily: DailyParquetResult?,
    /** Rows of the sources that opened, read or not, what the Android derive reports as aggregated. */
    val rowsRead: Long,
    /**
     * Sources that failed to open, decode or close. A failure before accumulation leaves no tiers for
     * that source. A later close failure, or a failure to reopen or decode it for the daily file, can
     * retain contributions already added to the tiers. The daily file is not written, and these partial
     * tiers must not be cached as current.
     */
    val failedSources: List<ArchiveRecord>,
    /** Why the daily file was not written although the sink was given, logged by the caller. */
    val dailyError: Exception?,
)

/**
 * One pass over a station-day's sources feeds both the tier [Aggregator] and the derived daily file
 * (#63). Each source is opened once to time its rows and feed the tiers. With a sink it is opened
 * once more and decoded only while its hourly row groups are written, then dropped, so the day's
 * columns are never all held at once (#98). Rows are timed (UTC or the same-boot estimate), kept when they fall in the UTC day, and
 * de-duplicated once with [RowKeys], so the tiers and the daily file count the same rows.
 *
 * A source that fails to open or decode is left out of the tiers, like before, and stops the daily
 * file, which is retried on the next pass. Sources open one at a time (#61).
 */
object DayPass {
    fun run(
        station: String,
        epochDay: Long,
        sources: List<DailyParquetSource>,
        sink: ParquetSink?,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long,
        tiers: Boolean = true,
        templates: FileTemplates = FileTemplates.BUNDLED,
    ): DayPassResult = DailyParquetBuilder.pass(
        station,
        epochDay,
        sources,
        sink,
        anchors,
        nowEpochS,
        if (tiers) Aggregator(station, templates = templates) else null,
        strict = false,
    )
}

/**
 * Shared Android/iOS derived UTC-day writer. Originals and their null UTC values are preserved;
 * same-boot estimates occupy a separate microsecond timestamp column. UTC instants are written as
 * `TIMESTAMP(MICROS, UTC)` under their current names (`event_time_utc_us`, `clock_anchor_utc_us`),
 * converted by unit from older nanosecond sources, and each device column keeps its `field_id`. Callers commit the sink only
 * after success. Sources are de-duplicated by boot and sequence and split at UTC day/hour boundaries.
 * The rows are written in timeline order with a page index, and `sorting_columns = [utc]` when no
 * estimate interleaves them.
 */
object DailyParquetBuilder {
    fun build(
        station: String,
        epochDay: Long,
        sources: List<DailyParquetSource>,
        sink: ParquetSink,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long,
    ): DailyParquetResult? = pass(station, epochDay, sources, sink, anchors, nowEpochS, aggregator = null, strict = true).daily

    /**
     * The estimated instant of each of [rows] rows from [mono] and [anchor]. A row without a monotonic
     * time, or one the anchor cannot place, is invalid, so no value of the Long range doubles as "none".
     */
    internal fun estimates(rows: Int, mono: LongColumn, anchor: TimeEstimate.Anchor): LongColumn {
        val values = LongArray(rows)
        val valid = BooleanArray(rows)
        for (i in 0 until rows) {
            if (!mono.has(i)) continue
            val ns = anchor.estimateNsOrNull(mono.values[i]) ?: continue
            values[i] = ns
            valid[i] = true
        }
        return LongColumn(values, valid)
    }

    /** One source after its first pass. Its columns are decoded later, only while its hours are written. */
    private class Src(
        val source: DailyParquetSource,
        val numRows: Long,
        val keepCount: Int,
        /** The estimated instant of each row, valid only where the anchor gave one. Null without an anchor. */
        val estimated: LongColumn?,
        /** The source's `opensensor` footer, null when it has none. */
        val footer: OpenSensorFooter?,
    ) {
        val row: ArchiveRecord get() = source.record
    }

    /** A source that could not be reopened or decoded for the daily file, apart from a sink failure. */
    private class SourceFailure(val record: ArchiveRecord, override val cause: Exception) : Exception(cause.message, cause)

    /**
     * The shared pass. [strict] throws the first source or schema failure, as the daily builder always
     * did. Otherwise a failing source is skipped for the tiers and recorded, and the daily file is dropped.
     */
    internal fun pass(
        station: String,
        epochDay: Long,
        sources: List<DailyParquetSource>,
        sink: ParquetSink?,
        anchors: Map<String, TimeEstimate.Anchor>,
        nowEpochS: Long,
        aggregator: Aggregator?,
        strict: Boolean,
    ): DayPassResult {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id" }
        val dayStartNs = epochDay * StationDays.DAY_NS
        val dayEndNs = dayStartNs + StationDays.DAY_NS
        var writing = sink != null
        var dailyError: Exception? = null
        val failed = ArrayList<ArchiveRecord>()
        var rowsRead = 0L

        // 1. Open one source at a time, time its rows, keep those of this day seen for the first time,
        // feed them to the tiers and close it before the next (#61). No other column is decoded yet (#98).
        // Estimated times (§4.6) per source: monotonic → UTC for unsynced files whose boot has an anchor.
        val opened = ArrayList<Src>()
        var anyEstimated = false
        val columnOrder = LinkedHashMap<String, ParquetType>()
        val timestampColumns = HashSet<String>()
        // Output name to the sources' field_id. Older files have none and take the id of the same name
        // from a newer source, dictionaries being append only. Ids that disagree, or that two names share,
        // are dropped.
        val fieldIds = HashMap<String, Int>()
        val conflictingIds = HashSet<String>()
        val seen = RowKeys()
        // Every kept row in source order: its timeline ns and its row inside its source. starts[s] is
        // the first kept row of opened source s.
        var keptUtc = LongArray(256)
        var keptRow = IntArray(256)
        var kept = 0
        val starts = ArrayList<Int>()
        for (source in sources.sortedWith(compareBy(SOURCE_ORDER) { it.record })) {
            val src = source.record
            val first = kept
            val one = try {
                require(src.station == station) { "source belongs to another station" }
                source.open().use { pf ->
                    val md = pf.metadata
                    val footer = md.footer
                    require(footer?.stationId == null || footer.stationId == station) { "Parquet source belongs to another station" }
                    rowsRead += pf.numRows
                    val cache = HashMap<String, ColumnValues?>()
                    fun col(name: String): ColumnValues? = cache.getOrPut(name) { md.leaf(name)?.let { pf.readColumn(it) } }
                    val utc = UtcColumns.readEventNs(pf)?.let(LongColumn::of) ?: return@use null
                    val bootHi = col("boot_id_hi")?.let(LongColumn::of)
                    val bootLo = col("boot_id_lo")?.let(LongColumn::of)
                    val seq = col(ParquetIndexer.SEQ)?.let(LongColumn::of)
                    val anchor = if (src.hasEstimatedUtc) anchors[src.bootId]?.takeIf { it.boot == pf.rowBootId() } else null
                    val mono = if (anchor != null) col(ParquetIndexer.MONO)?.let(LongColumn::of) else null
                    val estimated = if (anchor != null && mono != null) estimates(utc.size, mono, anchor) else null
                    val tierColumns = aggregator?.columns(::col, md.keyValueMetadata)
                    for (i in 0 until utc.size) {
                        val real = utc.has(i)
                        val ns = if (real) utc.values[i] else estimated?.takeIf { it.has(i) }?.values?.get(i) ?: continue
                        if (ns < dayStartNs || ns >= dayEndNs) continue
                        val bySeq = seq != null && seq.has(i)
                        if (!seen.firstSight(bootHi.at(i), bootLo.at(i), bySeq, if (bySeq) seq!!.values[i] else ns)) continue
                        if (tierColumns != null) aggregator.accumulate(tierColumns, i, ns, !real)
                        if (kept == keptUtc.size) {
                            keptUtc = keptUtc.copyOf(kept * 2)
                            keptRow = keptRow.copyOf(kept * 2)
                        }
                        keptUtc[kept] = ns
                        keptRow[kept] = i
                        kept++
                    }
                    val keepCount = kept - first
                    if (writing) {
                        try {
                            // A source whose rows are all duplicates (or outside the day) stays in the manifest with rows_used 0: provenance, not data.
                            for (leaf in pf.leaves) {
                                val name = UtcColumns.canonical(leaf.name)
                                val prev = columnOrder[name]
                                if (prev == null) columnOrder[name] = leaf.type
                                if (prev != null && prev != leaf.type) throw ParquetFormatException("'$name' is $prev in one file and ${leaf.type} in another")
                                if (UtcColumns.isUtcInstant(leaf)) {
                                    UtcColumns.nanosPerUnit(leaf)
                                    timestampColumns += name
                                }
                                leaf.fieldId?.let { id ->
                                    val known = fieldIds.getOrPut(name) { id }
                                    if (known != id) conflictingIds += name
                                }
                            }
                        } catch (e: ParquetFormatException) {
                            if (strict) throw e
                            dailyError = e
                            writing = false
                        }
                    }
                    if (estimated != null && keepCount > 0) anyEstimated = true
                    Src(source, pf.numRows, keepCount, estimated, footer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (strict) throw e
                // Rows already given to the tiers stay. Decoding fails before the row loop in practice.
                failed += src
                if (writing) {
                    dailyError = e
                    writing = false
                }
                kept = first
                null
            } ?: run {
                kept = first
                null
            } ?: continue
            starts += first
            opened += one
        }
        val buckets = aggregator?.buckets().orEmpty()
        if (!writing || opened.isEmpty() || kept == 0) return DayPassResult(buckets, null, rowsRead, failed, dailyError)
        starts += kept

        // 2. Sort the kept rows by timeline, real UTC or the estimate, keeping source order for ties.
        val order = IntArray(kept) { it }
        sortByTimeline(order, keptUtc)

        // 3. Columns to write: the union, the manifest index, and — when any row is estimated — the estimate.
        // UTC instants are TIMESTAMP(MICROS, UTC) like current device files, and every device column keeps
        // its field_id. The two columns the phone adds have no device id.
        // An id is also dropped when two names carry it, a field id must name one column.
        fieldIds.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }.forEach { conflictingIds += it }
        val columns = columnOrder.map { (name, type) ->
            ParquetWriter.Column(
                name,
                type,
                timestampUtc = ParquetLogicalType.TimeUnit.MICROS.takeIf { name in timestampColumns },
                fieldId = fieldIds[name]?.takeIf { name !in conflictingIds },
            )
        } +
            ParquetWriter.Column(SOURCE_INDEX, ParquetType.INT32) +
            (if (anyEstimated) listOf(ParquetWriter.Column(ESTIMATED_UTC, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS)) else emptyList())
        val utcIndex = columns.indexOfFirst { it.name == UtcColumns.EVENT_US }
        val startsArray = starts.toIntArray()

        // 4. Metadata.
        val appDay = appDay(opened.map { it.footer })
        val complete = nowEpochS >= (epochDay + 1) * 86_400 + COMPLETE_GRACE_S
        val meta = LinkedHashMap<String, String>().apply {
            appDay?.let { put(AppDay.KEY, it.encode()) }
            put("day", ArchiveLayout.dayLabel(epochDay))
            put("derived_by", BUILDER)
            put("derived_version", DERIVED_VERSION)
            put("source_count", opened.size.toString())
            put("coverage_rows", kept.toString())
            put("complete", complete.toString())
            if (anyEstimated) {
                put("estimated_rows", opened.filter { it.estimated != null }.sumOf { it.keepCount }.toString())
                put("time_estimate", "rows with null ${UtcColumns.EVENT_US} carry $ESTIMATED_UTC = anchor_utc_ns / 1000 + (monotonic_us - anchor_mono_us) from a same-boot UTC anchor; source/transport error unmeasured; drift not corrected (assume ±${TimeEstimate.DRIFT_PPM.toInt()} ppm)")
            }
        }

        // Keep the partition's timeline order. With estimates interleaved, raw UTC is not sorted.
        val writer = ParquetWriter(
            sink!!,
            columns,
            ParquetCodec.LZ4_RAW,
            meta,
            BUILDER,
            sortedBy = utcIndex.takeIf { it >= 0 && !anyEstimated },
            pageIndex = true,
        )
        // 5. One hourly row group at a time. A source is decoded when its first hour is written and
        // dropped after its last one, so memory holds the sources of about one hour, not the day (#98).
        val rows = Rows(order, keptRow, startsArray)
        val bounds = ArrayList<Int>()
        var at = 0
        while (at < kept) {
            bounds += at
            val hour = keptUtc[order[at]].floorDiv(HOUR_NS)
            while (at < kept && keptUtc[order[at]].floorDiv(HOUR_NS) == hour) at++
        }
        bounds += kept
        val lastGroup = IntArray(opened.size) { -1 }
        for (g in 0 until bounds.size - 1) for (j in bounds[g] until bounds[g + 1]) lastGroup[rows.source(j)] = g
        val live = arrayOfNulls<Map<String, ColumnValues>>(opened.size)
        var groups = 0
        try {
            writer.begin()
            for (g in 0 until bounds.size - 1) {
                val from = bounds[g]
                val to = bounds[g + 1]
                for (j in from until to) {
                    val s = rows.source(j)
                    if (live[s] == null) live[s] = decode(opened[s])
                }
                writer.rowGroup(gather(columns, opened, live, rows, from, to))
                groups++
                for (s in opened.indices) if (lastGroup[s] == g) live[s] = null
            }
            writer.finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceFailure) {
            if (strict) throw e.cause
            failed += e.record
            return DayPassResult(buckets, null, rowsRead, failed, e.cause)
        } catch (e: Exception) {
            if (strict) throw e
            // The daily output is rebuildable. A failed sink must not discard the already decoded tiers.
            return DayPassResult(buckets, null, rowsRead, failed, e)
        }
        val daily = DailyParquetResult(
            kept,
            columns.map { it.name },
            groups,
            complete,
            opened.map { s -> DailyParquetContribution(s.row, s.keepCount, anchors[s.row.bootId]?.sourceFile.takeIf { s.estimated != null }) },
        )
        return DayPassResult(buckets, daily, rowsRead, failed, null)
    }

    /**
     * Every column of [src], reopened, by canonical name. UTC instants are stored in microseconds under
     * their current name, whatever the source wrote. Each leaf is read the way the tiers' cache read it.
     */
    private fun decode(src: Src): Map<String, ColumnValues> = try {
        src.source.open().use { pf ->
            if (pf.numRows != src.numRows) throw ParquetFormatException("source changed between passes")
            val md = pf.metadata
            pf.leaves.associate { leaf ->
                val values = md.leaf(leaf.name)?.let { pf.readColumn(it) } ?: pf.readColumn(leaf)
                UtcColumns.canonical(leaf.name) to if (UtcColumns.isUtcInstant(leaf)) UtcColumns.toMicros(values, leaf) else values
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SourceFailure(src.row, e)
    }

    /** The kept rows in output order. Position [j] is kept row `order[j]`, of source [source] and row [row] there. */
    private class Rows(val order: IntArray, private val keptRow: IntArray, private val starts: IntArray) {
        fun source(j: Int): Int {
            val k = order[j]
            // The last start at or before k, a binary search over the opened sources.
            var lo = 0
            var hi = starts.size - 2
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= k) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun row(j: Int): Int = keptRow[order[j]]
    }

    /** A stable sort of [order] by `utc[order[i]]`, a no-op when the rows already come in order. */
    internal fun sortByTimeline(order: IntArray, utc: LongArray) {
        var sorted = true
        for (i in 1 until order.size) if (utc[order[i]] < utc[order[i - 1]]) { sorted = false; break }
        if (sorted) return
        var src = order
        var dst = IntArray(order.size)
        var width = 1
        while (width < order.size) {
            var lo = 0
            while (lo < order.size) {
                val mid = minOf(lo + width, order.size)
                val hi = minOf(lo + 2 * width, order.size)
                var a = lo
                var b = mid
                var o = lo
                while (a < mid && b < hi) dst[o++] = if (utc[src[b]] < utc[src[a]]) src[b++] else src[a++]
                while (a < mid) dst[o++] = src[a++]
                while (b < hi) dst[o++] = src[b++]
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        if (src !== order) src.copyInto(order)
    }

    /** Builds one row group's columns by gathering output rows [from] until [to] from the [live] decoded sources. */
    private fun gather(columns: List<ParquetWriter.Column>, opened: List<Src>, live: Array<Map<String, ColumnValues>?>, rows: Rows, from: Int, to: Int): List<ColumnValues> {
        val n = to - from
        val src = IntArray(n) { rows.source(from + it) }
        val row = IntArray(n) { rows.row(from + it) }
        return columns.map { c ->
            if (c.name == SOURCE_INDEX) {
                ColumnValues.Int32(src.copyOf(), null)
            } else if (c.name == ESTIMATED_UTC) {
                val v = LongArray(n); val valid = BooleanArray(n)
                for (k in 0 until n) {
                    val e = opened[src[k]].estimated ?: continue
                    if (e.has(row[k])) { v[k] = UtcColumns.nsToUs(e.values[row[k]]); valid[k] = true }
                }
                ColumnValues.Int64(v, valid.takeIf { it.any { b -> !b } })
            } else {
                val perSource = Array(opened.size) { live[it]?.get(c.name) }
                when (c.type) {
                    ParquetType.INT32 -> {
                        val v = IntArray(n); val valid = BooleanArray(n)
                        for (k in 0 until n) { val col = perSource[src[k]]; val i = row[k]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Int32).values[i]; valid[k] = true } }
                        ColumnValues.Int32(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.INT64 -> {
                        val v = LongArray(n); val valid = BooleanArray(n)
                        for (k in 0 until n) { val col = perSource[src[k]]; val i = row[k]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Int64).values[i]; valid[k] = true } }
                        ColumnValues.Int64(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.FLOAT -> {
                        val v = FloatArray(n); val valid = BooleanArray(n)
                        for (k in 0 until n) { val col = perSource[src[k]]; val i = row[k]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Float32).values[i]; valid[k] = true } }
                        ColumnValues.Float32(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.DOUBLE -> {
                        val v = DoubleArray(n); val valid = BooleanArray(n)
                        for (k in 0 until n) { val col = perSource[src[k]]; val i = row[k]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Float64).values[i]; valid[k] = true } }
                        ColumnValues.Float64(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.BYTE_ARRAY -> {
                        val offsets = IntArray(n + 1)
                        for (k in 0 until n) {
                            val col = perSource[src[k]] as? ColumnValues.Binary
                            offsets[k + 1] = offsets[k] + if (col != null && !col.isNull(row[k])) col.length(row[k]) else 0
                        }
                        val data = ByteArray(offsets[n])
                        val valid = BooleanArray(n)
                        for (k in 0 until n) {
                            val col = perSource[src[k]] as? ColumnValues.Binary ?: continue
                            val i = row[k]
                            if (col.isNull(i)) continue
                            valid[k] = true
                            col.data.copyInto(data, offsets[k], col.offsets[i], col.offsets[i + 1])
                        }
                        ColumnValues.Binary(offsets, data, valid.takeIf { it.any { b -> !b } })
                    }
                    else -> throw ParquetFormatException("unsupported derived column type ${c.type}")
                }
            }
        }
    }

    const val BUILDER = "opensensor.space shared derived-v4"

    /** The `derived_version` of a day file. A change to the day metadata bumps it, with [BUILDER]. */
    const val DERIVED_VERSION = "4"
    const val SOURCE_INDEX = "source_file_index"
    const val ESTIMATED_UTC = "event_time_utc_estimated_us"
    const val HOUR_NS = 3_600L * 1_000_000_000L
    const val COMPLETE_GRACE_S = 30 * 60L

    /** The daily builder's established duplicate preference, real UTC first, then source name. */
    val SOURCE_ORDER: Comparator<ArchiveRecord> = compareBy({ it.firstUtcNs ?: Long.MAX_VALUE }, { it.name })

    /**
     * The app-private [AppDay] entry of a day file built from sources with [footers], null when no
     * source says anything. It holds only what the app reads back. The shortest positive interval, as a
     * tier bucket keeps it, and each digest only when every source names the same one. A day mixing
     * dictionaries or templates carries none, and its rows go back to the raw files. The day file never
     * writes the `opensensor` key, because it cannot fill a value that validates against
     * stac-opensensor `footer.json`.
     */
    fun appDay(footers: List<OpenSensorFooter?>): AppDay? {
        fun <T> agreed(pick: (OpenSensorFooter?) -> T?): T? = footers.map(pick).distinct().singleOrNull()
        val day = AppDay(
            sampleIntervalMs = footers.mapNotNull { it?.sampleIntervalMs }.minOrNull(),
            dict = agreed { it?.dictionarySha256 },
            desc = agreed { it?.templateSha256 },
        )
        return day.takeIf { it != AppDay() }
    }
}
