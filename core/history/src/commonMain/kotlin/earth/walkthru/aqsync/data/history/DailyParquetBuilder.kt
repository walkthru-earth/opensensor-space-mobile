package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter

/**
 * One immutable source and its verified archive identity. [DailyParquetBuilder] calls [open] once and
 * closes the file before it opens the next source, so a day holds at most one source file open (#61).
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

/**
 * Shared Android/iOS derived UTC-day writer. Originals and their null UTC values are preserved;
 * same-boot estimates occupy a separate nanosecond timestamp column. Callers commit the sink only
 * after success. Sources are de-duplicated by boot and sequence and split at UTC day/hour boundaries.
 */
object DailyParquetBuilder {
    fun build(
        station: String,
        epochDay: Long,
        sources: List<DailyParquetSource>,
        sink: ParquetSink,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long,
    ): DailyParquetResult? {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id" }
        val dayStartNs = epochDay * StationDays.DAY_NS
        val dayEndNs = dayStartNs + StationDays.DAY_NS

        // 1. Open one source at a time, gather its columns and the row indices that belong to this day,
        // decode what the day needs and close it before the next (#61). Estimated times (§4.6) per source:
        // monotonic → UTC for unsynced files whose boot has an anchor.
        class Src(
            val row: ArchiveRecord,
            val keep: IntArray,
            val estimated: LongArray?,
            val decoded: Map<String, ColumnValues>,
            val keyValueMetadata: Map<String, String>,
            val schemaVersion: String?,
        )
        val opened = ArrayList<Src>()
        var anyEstimated = false
        val columnOrder = LinkedHashMap<String, ParquetType>()
        val timestampColumns = HashSet<String>()
        val seen = RowKeys()
        for (source in sources.sortedWith(compareBy({ it.record.firstUtcNs ?: Long.MAX_VALUE }, { it.record.name }))) {
            val src = source.record
            require(src.station == station) { "source belongs to another station" }
            opened += source.open().use { pf ->
                val md = pf.metadata
                require(md.stationId == null || md.stationId == station) { "Parquet source belongs to another station" }
                val utc = md.leaf(ParquetIndexer.UTC)?.let { pf.readColumn(it) } ?: return@use null
                val bootHi = md.leaf("boot_id_hi")?.let { pf.readColumn(it) }
                val bootLo = md.leaf("boot_id_lo")?.let { pf.readColumn(it) }
                val seq = md.leaf(ParquetIndexer.SEQ)?.let { pf.readColumn(it) }
                val anchor = if (src.hasEstimatedUtc) anchors[src.bootId]?.takeIf { it.boot == md.bootId } else null
                val mono = if (anchor != null) md.leaf(ParquetIndexer.MONO)?.let { pf.readColumn(it) } else null
                val estimated = if (anchor != null && mono != null) LongArray(utc.size) { i -> mono.asLong(i)?.let(anchor::estimateNsOrNull) ?: Long.MIN_VALUE } else null
                val keep = ArrayList<Int>()
                for (i in 0 until utc.size) {
                    val ns = utc.asLong(i) ?: estimated?.get(i)?.takeIf { it != Long.MIN_VALUE } ?: continue
                    if (ns < dayStartNs || ns >= dayEndNs) continue
                    if (!seen.firstSight(bootHi?.asLong(i), bootLo?.asLong(i), seq?.asLong(i), ns)) continue
                    keep += i
                }
                // A source whose rows are all duplicates (or outside the day) stays in the manifest with rows_used 0: provenance, not data.
                for (leaf in pf.leaves) {
                    val prev = columnOrder[leaf.name]
                    if (prev == null) columnOrder[leaf.name] = leaf.type
                    if (prev != null && prev != leaf.type) throw ParquetFormatException("'${leaf.name}' is $prev in one file and ${leaf.type} in another")
                    if (leaf.isTimestampNanosUtc) timestampColumns += leaf.name
                }
                if (estimated != null && keep.isNotEmpty()) anyEstimated = true
                // Decode every column of a contributing source once, while it is open.
                val decoded = if (keep.isEmpty()) emptyMap() else pf.leaves.associate { it.name to if (it.name == ParquetIndexer.UTC) utc else pf.readColumn(it) }
                Src(src, keep.toIntArray(), estimated, decoded, md.keyValueMetadata, md.schemaVersion)
            } ?: continue
        }
        if (opened.isEmpty() || opened.all { it.keep.isEmpty() }) return null
        // The two *_utc_ns columns are annotated even when the sources (v1/v2) did not annotate them.
        timestampColumns += listOf(ParquetIndexer.UTC, "clock_anchor_utc_ns").filter { it in columnOrder }

        // 2. Flatten to (source index, row index, timeline utc) sorted by time — real UTC or the estimate.
        data class Ref(val src: Int, val row: Int, val utcNs: Long)
        val refs = ArrayList<Ref>()
        opened.forEachIndexed { s, src ->
            val utc = src.decoded[ParquetIndexer.UTC] ?: return@forEachIndexed
            for (i in src.keep) refs += Ref(s, i, utc.asLong(i) ?: src.estimated!![i])
        }
        refs.sortBy { it.utcNs }

        // 3. Columns to write: the union, the manifest index, and — when any row is estimated — the estimate.
        val columns = columnOrder.map { (name, type) -> ParquetWriter.Column(name, type, timestampNanosUtc = name in timestampColumns) } +
            ParquetWriter.Column(SOURCE_INDEX, ParquetType.INT32) +
            (if (anyEstimated) listOf(ParquetWriter.Column(ESTIMATED_UTC, ParquetType.INT64, timestampNanosUtc = true)) else emptyList())
        val utcIndex = columns.indexOfFirst { it.name == ParquetIndexer.UTC }
        val estimatedBySource: List<LongArray?> = opened.map { it.estimated }
        val decoded: List<Map<String, ColumnValues>> = opened.map { it.decoded }

        // 4. Metadata.
        val invariant = LinkedHashMap<String, String>()
        val first = opened.first().keyValueMetadata
        for ((k, v) in first) if (k in COPIED_METADATA && opened.all { it.keyValueMetadata[k] == v }) invariant[k] = v
        val complete = nowEpochS >= (epochDay + 1) * 86_400 + COMPLETE_GRACE_S
        val meta = LinkedHashMap<String, String>().apply {
            putAll(invariant)
            put("station_id", station)
            put("day", ArchiveLayout.dayLabel(epochDay))
            put("derived_by", BUILDER)
            put("derived_version", "2")
            put("source_count", opened.size.toString())
            put("coverage_rows", refs.size.toString())
            put("complete", complete.toString())
            put("schema_versions", opened.map { it.schemaVersion ?: "?" }.distinct().joinToString(","))
            put("purpose", "telemetry-derived-day")
            if (anyEstimated) {
                put("estimated_rows", refs.count { r -> opened[r.src].estimated != null }.toString())
                put("time_estimate", "rows with null event_time_utc_ns carry $ESTIMATED_UTC = anchor_utc_ns + (monotonic_us - anchor_mono_us) * 1000 from a same-boot UTC anchor; source/transport error unmeasured; drift not corrected (assume ±${TimeEstimate.DRIFT_PPM.toInt()} ppm)")
            }
        }

        // Keep the partition's timeline order. With estimates interleaved, raw UTC is not sorted.
        val writer = ParquetWriter(sink, columns, ParquetCodec.LZ4_RAW, meta, BUILDER, sortedBy = utcIndex.takeIf { it >= 0 && !anyEstimated })
        writer.begin()
        var groups = 0
        var start = 0
        while (start < refs.size) {
            val hour = refs[start].utcNs.floorDiv(HOUR_NS)
            var end = start
            while (end < refs.size && refs[end].utcNs.floorDiv(HOUR_NS) == hour) end++
            writer.rowGroup(gather(columns, decoded, estimatedBySource, refs.subList(start, end).map { it.src to it.row }))
            groups++
            start = end
        }
        writer.finish()
        return DailyParquetResult(
            refs.size,
            columns.map { it.name },
            groups,
            complete,
            opened.map { s -> DailyParquetContribution(s.row, s.keep.size, anchors[s.row.bootId]?.sourceFile.takeIf { s.estimated != null }) },
        )
    }

    /** Builds one row group's columns by gathering rows (source, index) from the decoded sources. */
    private fun gather(columns: List<ParquetWriter.Column>, decoded: List<Map<String, ColumnValues>>, estimated: List<LongArray?>, rows: List<Pair<Int, Int>>): List<ColumnValues> {
        val n = rows.size
        return columns.map { c ->
            if (c.name == SOURCE_INDEX) {
                ColumnValues.Int32(IntArray(n) { rows[it].first }, null)
            } else if (c.name == ESTIMATED_UTC) {
                val v = LongArray(n); val valid = BooleanArray(n)
                rows.forEachIndexed { k, (s, i) -> estimated[s]?.get(i)?.takeIf { it != Long.MIN_VALUE }?.let { v[k] = it; valid[k] = true } }
                ColumnValues.Int64(v, valid.takeIf { it.any { b -> !b } })
            } else {
                when (c.type) {
                    ParquetType.INT32 -> {
                        val v = IntArray(n); val valid = BooleanArray(n)
                        rows.forEachIndexed { k, (s, i) -> val col = decoded[s][c.name]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Int32).values[i]; valid[k] = true } }
                        ColumnValues.Int32(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.INT64 -> {
                        val v = LongArray(n); val valid = BooleanArray(n)
                        rows.forEachIndexed { k, (s, i) -> val col = decoded[s][c.name]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Int64).values[i]; valid[k] = true } }
                        ColumnValues.Int64(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.FLOAT -> {
                        val v = FloatArray(n); val valid = BooleanArray(n)
                        rows.forEachIndexed { k, (s, i) -> val col = decoded[s][c.name]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Float32).values[i]; valid[k] = true } }
                        ColumnValues.Float32(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.DOUBLE -> {
                        val v = DoubleArray(n); val valid = BooleanArray(n)
                        rows.forEachIndexed { k, (s, i) -> val col = decoded[s][c.name]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Float64).values[i]; valid[k] = true } }
                        ColumnValues.Float64(v, valid.takeIf { it.any { b -> !b } })
                    }
                    ParquetType.BYTE_ARRAY -> {
                        val values = rows.map { (s, i) -> (decoded[s][c.name] as? ColumnValues.Binary)?.bytes(i) }
                        val offsets = IntArray(n + 1)
                        values.forEachIndexed { i, bytes -> offsets[i + 1] = offsets[i] + (bytes?.size ?: 0) }
                        val data = ByteArray(offsets.last())
                        values.forEachIndexed { i, bytes -> bytes?.copyInto(data, offsets[i]) }
                        ColumnValues.Binary(offsets, data, BooleanArray(n) { values[it] != null }.takeIf { it.any { b -> !b } })
                    }
                    else -> throw ParquetFormatException("unsupported derived column type ${c.type}")
                }
            }
        }
    }

    const val BUILDER = "opensensor.space shared derived-v2"
    const val SOURCE_INDEX = "source_file_index"
    const val ESTIMATED_UTC = "event_time_utc_estimated_ns"
    const val HOUR_NS = 3_600L * 1_000_000_000L
    const val COMPLETE_GRACE_S = 30 * 60L
    private val COPIED_METADATA = setOf("board", "dictionary_version", "dictionary_uri", "dictionary_sha256", "device_id", "pms_status", "light_status", "proximity_status", "magnetic_units", "unavailable", "sample_interval_ms", "clock", "time_semantics")
}
