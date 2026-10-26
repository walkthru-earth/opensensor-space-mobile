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
import space.opensensor.model.BundledCatalog
import space.opensensor.model.GateValue
import space.opensensor.model.ValidityGate

// The two-pass history code as it was before #63, frozen as the oracle of HistoryGoldenTest. Each pass
// decodes a source on its own and de-duplicates with a HashSet of RowKey objects. Do not optimise it.

internal class LegacyRowKeys {
    private val seen = HashSet<RowKey>()

    fun firstSight(bootHi: Long?, bootLo: Long?, sequence: Long?, timelineNs: Long): Boolean = seen.add(RowKey.of(bootHi, bootLo, sequence, timelineNs))
}

/** The oracle's own row gate, so the benchmark build needs nothing internal. The bundled templates use these two forms. */
private class LegacyGate(private val gate: ValidityGate, private val column: ColumnValues?) {
    fun passes(i: Int): Boolean {
        val v = column?.asDouble(i) ?: return false
        return when (gate) {
            is ValidityGate.Finite -> v.isFinite()
            is ValidityGate.Equals -> (gate.value as? GateValue.Number)?.value == v
        }
    }
}

internal class LegacyAggregator(
    private val station: String,
    private val resolutions: Set<Resolution> = Resolution.entries.toSet(),
    private val metrics: List<MetricSpec> = Metrics.all,
) {
    private class Acc(metricCount: Int) {
        var rows = 0
        var estimated = 0
        var intervalMs = 0L
        val n = IntArray(metricCount)
        val settling = IntArray(metricCount)
        val sum = DoubleArray(metricCount)
        val min = FloatArray(metricCount) { Float.POSITIVE_INFINITY }
        val max = FloatArray(metricCount) { Float.NEGATIVE_INFINITY }
    }

    private val accs = HashMap<Pair<Resolution, Long>, Acc>()
    private val seen = LegacyRowKeys()

    /** Adds every timed row of [file]; with an [anchor] for the file's boot, unsynced rows get an estimated time. */
    fun add(file: ParquetFile, anchor: TimeEstimate.Anchor? = null) {
        val md = file.metadata
        val utc = UtcColumns.readEventNs(file) ?: return
        fun col(name: String): ColumnValues? = md.leaf(name)?.let { file.readColumn(it) }
        val applicable = anchor?.takeIf { it.boot == file.rowBootId() }
        val monoAll = col(ParquetIndexer.MONO)
        val mono = if (applicable != null) monoAll else null
        val bootHi = col("boot_id_hi")
        val bootLo = col("boot_id_lo")
        val seq = col(ParquetIndexer.SEQ)
        val values = metrics.map { col(it.column) }
        val humidity = metrics.mapNotNull { it.humidityColumn }.distinct().associateWith { col(it) }
        val gates = metrics.map { spec -> spec.gate?.let { g -> LegacyGate(g, col(g.column)) } }
        // The footer interval, else the CoreS3 default the tests read every file with.
        val interval = OpenSensorFooter.of(md.keyValueMetadata)?.sampleIntervalMs
            ?: (BundledCatalog.templates.single { it.id.startsWith("cores3-") }.defaultSampleIntervalS * 1000).toLong()
        for (i in 0 until utc.size) {
            var estimated = false
            val ns = utc.asLong(i) ?: run {
                val m = mono?.asLong(i) ?: return@run null
                estimated = true
                applicable?.estimateNsOrNull(m)
            } ?: continue
            if (!seen.firstSight(bootHi?.asLong(i), bootLo?.asLong(i), seq?.asLong(i), ns)) continue
            val s = ns.floorDiv(1_000_000_000L)
            for (r in resolutions) {
                val acc = accs.getOrPut(r to r.bucketStart(s)) { Acc(metrics.size) }
                acc.rows++
                if (estimated) acc.estimated++
                if (acc.intervalMs == 0L || interval < acc.intervalMs) acc.intervalMs = interval
                for ((k, spec) in metrics.withIndex()) {
                    val colv = values[k] ?: continue
                    if (gates[k]?.passes(i) == false) continue
                    val v = colv.asDouble(i)?.let { raw -> spec.valueOf(raw, spec.humidityColumn?.let { h -> humidity[h]?.asDouble(i) }) } ?: continue
                    if (v.isNaN() || v.isInfinite()) continue
                    acc.n[k]++
                    if (spec.settleSeconds > 0 && (monoAll?.asLong(i) ?: Long.MAX_VALUE) < spec.settleSeconds * 1_000_000L) acc.settling[k]++
                    acc.sum[k] += v
                    val f = v.toFloat()
                    if (f < acc.min[k]) acc.min[k] = f
                    if (f > acc.max[k]) acc.max[k] = f
                }
            }
        }
    }

    fun buckets(): List<Bucket> = accs.entries
        .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
        .map { (key, a) ->
            val per = LinkedHashMap<String, MetricAgg>()
            for ((k, spec) in metrics.withIndex()) {
                if (a.n[k] > 0) per[spec.id] = MetricAgg(a.n[k], (a.sum[k] / a.n[k]).toFloat(), a.min[k], a.max[k], a.settling[k])
            }
            Bucket(station, key.first, key.second, a.rows, a.estimated, per, a.intervalMs)
        }
}

internal object LegacyDailyParquetBuilder {
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
        val seen = LegacyRowKeys()
        for (source in sources.sortedWith(compareBy({ it.record.firstUtcNs ?: Long.MAX_VALUE }, { it.record.name }))) {
            val src = source.record
            require(src.station == station) { "source belongs to another station" }
            opened += source.open().use { pf ->
                val md = pf.metadata
                require(md.footer?.stationId == null || md.footer?.stationId == station) { "Parquet source belongs to another station" }
                val utc = UtcColumns.readEventNs(pf) ?: return@use null
                val bootHi = md.leaf("boot_id_hi")?.let { pf.readColumn(it) }
                val bootLo = md.leaf("boot_id_lo")?.let { pf.readColumn(it) }
                val seq = md.leaf(ParquetIndexer.SEQ)?.let { pf.readColumn(it) }
                val anchor = if (src.hasEstimatedUtc) anchors[src.bootId]?.takeIf { it.boot == pf.rowBootId() } else null
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
                    val name = UtcColumns.canonical(leaf.name)
                    val prev = columnOrder[name]
                    if (prev == null) columnOrder[name] = leaf.type
                    if (prev != null && prev != leaf.type) throw ParquetFormatException("'$name' is $prev in one file and ${leaf.type} in another")
                    if (UtcColumns.isUtcInstant(leaf)) timestampColumns += name
                }
                if (estimated != null && keep.isNotEmpty()) anyEstimated = true
                // Decode every column of a contributing source once, while it is open.
                val decoded = if (keep.isEmpty()) emptyMap() else pf.leaves.associate { leaf -> UtcColumns.canonical(leaf.name) to if (UtcColumns.isUtcInstant(leaf)) UtcColumns.toMicros(pf.readColumn(leaf), leaf) else pf.readColumn(leaf) }
                Src(src, keep.toIntArray(), estimated, decoded, md.keyValueMetadata, null)
            } ?: continue
        }
        if (opened.isEmpty() || opened.all { it.keep.isEmpty() }) return null

        // 2. Flatten to (source index, row index, timeline utc) sorted by time — real UTC or the estimate.
        data class Ref(val src: Int, val row: Int, val utcNs: Long)
        val refs = ArrayList<Ref>()
        opened.forEachIndexed { s, src ->
            val utc = src.decoded[UtcColumns.EVENT_US] ?: return@forEachIndexed
            for (i in src.keep) refs += Ref(s, i, utc.asLong(i)?.times(1_000L) ?: src.estimated!![i])
        }
        refs.sortBy { it.utcNs }

        // 3. Columns to write: the union, the manifest index, and — when any row is estimated — the estimate.
        val columns = columnOrder.map { (name, type) -> ParquetWriter.Column(name, type, timestampUtc = ParquetLogicalType.TimeUnit.MICROS.takeIf { name in timestampColumns }) } +
            ParquetWriter.Column(SOURCE_INDEX, ParquetType.INT32) +
            (if (anyEstimated) listOf(ParquetWriter.Column(ESTIMATED_UTC, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS)) else emptyList())
        val utcIndex = columns.indexOfFirst { it.name == UtcColumns.EVENT_US }
        val estimatedBySource: List<LongArray?> = opened.map { it.estimated }
        val decoded: List<Map<String, ColumnValues>> = opened.map { it.decoded }

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
            put("coverage_rows", refs.size.toString())
            put("complete", complete.toString())
            put("purpose", "telemetry-derived-day")
            if (anyEstimated) {
                put("estimated_rows", refs.count { r -> opened[r.src].estimated != null }.toString())
                put("time_estimate", "rows with null event_time_utc_us carry $ESTIMATED_UTC = anchor_utc_ns / 1000 + (monotonic_us - anchor_mono_us) from a same-boot UTC anchor; source/transport error unmeasured; drift not corrected (assume ±${TimeEstimate.DRIFT_PPM.toInt()} ppm)")
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
                rows.forEachIndexed { k, (s, i) -> estimated[s]?.get(i)?.takeIf { it != Long.MIN_VALUE }?.let { v[k] = it.floorDiv(1_000L); valid[k] = true } }
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

    const val BUILDER = "opensensor.space shared derived-v3"
    const val SOURCE_INDEX = "source_file_index"
    const val ESTIMATED_UTC = "event_time_utc_estimated_us"
    const val HOUR_NS = 3_600L * 1_000_000_000L
    const val COMPLETE_GRACE_S = 30 * 60L
}
