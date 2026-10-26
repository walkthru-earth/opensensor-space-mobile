package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlinx.coroutines.CancellationException

/**
 * The daily builder as it was before #98, frozen as the oracle of [DailyParquetBuilder]. It decodes
 * every column of every contributing source in its first pass and holds them all until the file is
 * written. HistoryGoldenTest checks the streaming builder writes the same bytes, and
 * DailyParquetMemoryTest measures both. Never edit it to follow the builder.
 */
object PreStreamingDailyParquetBuilder {
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

    /** One open source after its pass. [decoded] holds every column of a contributing source, by leaf name. */
    private class Src(
        val row: ArchiveRecord,
        val keepCount: Int,
        /** The estimated instant of each row, valid only where the anchor gave one. Null without an anchor. */
        val estimated: LongColumn?,
        val decoded: Map<String, ColumnValues>,
        val keyValueMetadata: Map<String, String>,
        val schemaVersion: String?,
    )

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
        // feed them to the tiers, decode what the daily file needs and close it before the next (#61).
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
                    require(md.footer?.stationId == null || md.footer?.stationId == station) { "Parquet source belongs to another station" }
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
                    // Decode every column of a contributing source once, while it is open. The tiers'
                    // columns are already in the cache.
                    // UTC instants are stored in microseconds under their current name, whatever the source wrote.
                    val decoded = if (!writing || keepCount == 0) {
                        emptyMap()
                    } else {
                        pf.leaves.associate { leaf ->
                            val values = col(leaf.name) ?: pf.readColumn(leaf)
                            UtcColumns.canonical(leaf.name) to if (UtcColumns.isUtcInstant(leaf)) UtcColumns.toMicros(values, leaf) else values
                        }
                    }
                    Src(src, keepCount, estimated, decoded, md.keyValueMetadata, null)
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
        // The footer rule is the production one, these oracles check the rows and bytes around it.
        val footer = DailyParquetBuilder.dayFooter(opened.map { OpenSensorFooter.of(it.keyValueMetadata) })
        val complete = nowEpochS >= (epochDay + 1) * 86_400 + COMPLETE_GRACE_S
        val meta = LinkedHashMap<String, String>().apply {
            footer?.let { put(OpenSensorFooter.DAY_KEY, it.encode()) }
            put("station_id", station)
            put("day", ArchiveLayout.dayLabel(epochDay))
            put("derived_by", BUILDER)
            put("derived_version", "3")
            put("source_count", opened.size.toString())
            put("coverage_rows", kept.toString())
            put("complete", complete.toString())
            put("purpose", "telemetry-derived-day")
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
        var groups = 0
        try {
            writer.begin()
            var start = 0
            val rows = Rows(order, keptRow, startsArray)
            while (start < kept) {
                val hour = keptUtc[order[start]].floorDiv(HOUR_NS)
                var end = start
                while (end < kept && keptUtc[order[end]].floorDiv(HOUR_NS) == hour) end++
                writer.rowGroup(gather(columns, opened, rows, start, end))
                groups++
                start = end
            }
            writer.finish()
        } catch (e: CancellationException) {
            throw e
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

    /** Builds one row group's columns by gathering output rows [from] until [to] from the decoded sources. */
    private fun gather(columns: List<ParquetWriter.Column>, opened: List<Src>, rows: Rows, from: Int, to: Int): List<ColumnValues> {
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
                val perSource = Array(opened.size) { opened[it].decoded[c.name] }
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

    const val BUILDER = "opensensor.space shared derived-v3"
    const val SOURCE_INDEX = "source_file_index"
    const val ESTIMATED_UTC = "event_time_utc_estimated_us"
    const val HOUR_NS = 3_600L * 1_000_000_000L
    const val COMPLETE_GRACE_S = 30 * 60L

    /** The daily builder's established duplicate preference, real UTC first, then source name. */
    val SOURCE_ORDER: Comparator<ArchiveRecord> = compareBy({ it.firstUtcNs ?: Long.MAX_VALUE }, { it.name })
}
