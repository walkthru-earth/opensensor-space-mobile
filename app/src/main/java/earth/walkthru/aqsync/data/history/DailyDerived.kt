package earth.walkthru.aqsync.data.history

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Where the derived layer lives: `filesDir/derived/v1/<station>/day=YYYY-MM-DD.parquet` + `.manifest.json`. Never inside `archive/`. */
@Singleton
class DerivedStore(baseDir: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    val root: File = File(baseDir, "derived/v1")

    fun dayFile(station: String, epochDay: Long): File = File(File(root, station), "day=${dayLabel(epochDay)}.parquet")
    fun manifestFile(station: String, epochDay: Long): File = File(File(root, station), "day=${dayLabel(epochDay)}.manifest.json")

    /** Every daily file on this phone, as (station, file). */
    fun listAll(): List<Pair<String, File>> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).flatMap { dir -> (dir.listFiles { f -> f.isFile && f.extension == "parquet" } ?: emptyArray()).map { dir.name to it } }

    companion object {
        fun dayLabel(epochDay: Long): String = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epochDay * 86_400))
    }
}

@Serializable
data class DailyManifest(
    val station: String,
    val day: String,
    /** Verified device files the day was built from (`archive/<station>/<name>`), in time order. */
    val sources: List<Source>,
    val rows: Int,
    val columns: List<String>,
    /** Every known file for the day is in, and the UTC day ended more than 30 min ago on the phone clock. */
    val complete: Boolean,
    val builder: String,
    @kotlinx.serialization.SerialName("built_at") val builtAt: String,
    @kotlinx.serialization.SerialName("source_signature") val sourceSignature: String,
) {
    @Serializable
    data class Source(
        val name: String,
        val bytes: Long,
        val crc32: Long,
        val sha256: String,
        @kotlinx.serialization.SerialName("rows_used") val rowsUsed: Int,
        /** Set when this source's rows are dated by an estimate: the fully-timed file of the same boot that anchored them. */
        @kotlinx.serialization.SerialName("time_anchor") val timeAnchor: String? = null,
    )
}

data class DailyBuildResult(val file: File, val manifest: File, val rows: Int, val columns: Int, val rowGroups: Int, val complete: Boolean)

/**
 * Builds one derived daily Parquet file per UTC station-day from the verified device files:
 *
 * - every row whose `event_time_utc_ns` falls in the day, in time order, de-duplicated on
 *   `(boot_id_hi, boot_id_lo, sequence)`;
 * - the union of the sources' columns in first-seen order (v1 files leave the four v2 columns null),
 *   plus `source_file_index` (INT32 into the manifest's `sources`) — flat numeric only, so the app's
 *   own reader reads the file back; the station is in the path and the metadata as on the SD card;
 * - one row group per UTC hour (≤ 360 rows), LZ4_RAW, footer statistics, `TIMESTAMP(NANOS, UTC)` on
 *   the two `*_utc_ns` columns;
 * - metadata copied from the sources where it is invariant (`station_id`, `dictionary_*`, `board`, …)
 *   plus `derived_by`, `derived_version`, `source_count`, `coverage_rows`, `complete`, `day`.
 *
 * Written atomically (temp + rename) next to a JSON manifest. Never touches `archive/`.
 */
class DailyDerivedBuilder(private val store: DerivedStore, private val fileFor: (station: String, name: String) -> File) {

    fun build(
        station: String,
        epochDay: Long,
        sources: List<ArchivedFile>,
        sourceSignature: String,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long = System.currentTimeMillis() / 1000,
    ): DailyBuildResult? {
        val dayStartNs = epochDay * HistoryDeriver.DAY_NS
        val dayEndNs = dayStartNs + HistoryDeriver.DAY_NS

        // 1. Open every source, gather its columns and the row indices that belong to this day.
        // Estimated times (§4.6) per source: monotonic → UTC for unsynced files whose boot has an anchor.
        class Src(val row: ArchivedFile, val file: ParquetFile, val keep: IntArray, val estimated: LongArray?)
        val opened = ArrayList<Src>()
        var anyEstimated = false
        val columnOrder = LinkedHashMap<String, ParquetType>()
        val timestampColumns = HashSet<String>()
        val seenExact = HashSet<Triple<Long, Long, Long>>()
        for (src in sources.sortedBy { it.firstUtcNs ?: Long.MAX_VALUE }) {
            val f = fileFor(src.station, src.name)
            if (!f.isFile) continue
            val pf = try { ParquetFile.open(f) } catch (e: ParquetFormatException) { continue }
            val md = pf.metadata
            val utc = md.leaf(ParquetIndexer.UTC)?.let { pf.readColumn(it) } ?: continue
            val bootHi = md.leaf("boot_id_hi")?.let { pf.readColumn(it) }
            val bootLo = md.leaf("boot_id_lo")?.let { pf.readColumn(it) }
            val seq = md.leaf(ParquetIndexer.SEQ)?.let { pf.readColumn(it) }
            val anchor = if (src.hasEstimatedUtc) anchors[src.bootId]?.takeIf { it.boot == md.bootId } else null
            val mono = if (anchor != null) md.leaf(ParquetIndexer.MONO)?.let { pf.readColumn(it) } else null
            val estimated = if (anchor != null && mono != null) LongArray(utc.size) { i -> mono.asLong(i)?.let(anchor::estimateNs) ?: Long.MIN_VALUE } else null
            val keep = ArrayList<Int>()
            for (i in 0 until utc.size) {
                val ns = utc.asLong(i) ?: estimated?.get(i)?.takeIf { it != Long.MIN_VALUE } ?: continue
                if (ns < dayStartNs || ns >= dayEndNs) continue
                val key = Triple(bootHi?.asLong(i) ?: 0L, bootLo?.asLong(i) ?: 0L, seq?.asLong(i) ?: (ns))
                if (!seenExact.add(key)) continue
                keep += i
            }
            // A source whose rows are all duplicates (or outside the day) stays in the manifest with rows_used 0: provenance, not data.
            for (leaf in pf.leaves) {
                val prev = columnOrder.putIfAbsent(leaf.name, leaf.type)
                if (prev != null && prev != leaf.type) throw ParquetFormatException("'${leaf.name}' is $prev in one file and ${leaf.type} in another")
                if (leaf.isTimestampNanosUtc) timestampColumns += leaf.name
            }
            if (estimated != null && keep.isNotEmpty()) anyEstimated = true
            opened += Src(src, pf, keep.toIntArray(), estimated)
        }
        if (opened.isEmpty() || opened.all { it.keep.isEmpty() }) return null
        // The two *_utc_ns columns are annotated even when the sources (v1/v2) did not annotate them.
        timestampColumns += listOf(ParquetIndexer.UTC, "clock_anchor_utc_ns").filter { it in columnOrder }

        // 2. Flatten to (source index, row index, timeline utc) sorted by time — real UTC or the estimate.
        data class Ref(val src: Int, val row: Int, val utcNs: Long)
        val refs = ArrayList<Ref>()
        opened.forEachIndexed { s, src ->
            val utc = src.file.readColumn(ParquetIndexer.UTC)
            for (i in src.keep) refs += Ref(s, i, utc.asLong(i) ?: src.estimated!![i])
        }
        refs.sortBy { it.utcNs }

        // 3. Columns to write: the union, the manifest index, and — when any row is estimated — the estimate.
        val columns = columnOrder.map { (name, type) -> ParquetWriter.Column(name, type, timestampNanosUtc = name in timestampColumns) } +
            ParquetWriter.Column(SOURCE_INDEX, ParquetType.INT32) +
            (if (anyEstimated) listOf(ParquetWriter.Column(ESTIMATED_UTC, ParquetType.INT64, timestampNanosUtc = true)) else emptyList())
        val utcIndex = columns.indexOfFirst { it.name == ParquetIndexer.UTC }
        val estimatedBySource: List<LongArray?> = opened.map { it.estimated }

        // Decode every needed column of every source once.
        val decoded: List<Map<String, ColumnValues>> = opened.map { src -> src.file.leaves.associate { it.name to src.file.readColumn(it) } }

        // 4. Metadata.
        val invariant = LinkedHashMap<String, String>()
        val first = opened.first().file.metadata.keyValueMetadata
        for ((k, v) in first) if (k in COPIED_METADATA && opened.all { it.file.metadata.keyValueMetadata[k] == v }) invariant[k] = v
        val complete = nowEpochS >= (epochDay + 1) * 86_400 + COMPLETE_GRACE_S
        val meta = LinkedHashMap<String, String>().apply {
            putAll(invariant)
            put("station_id", station)
            put("day", DerivedStore.dayLabel(epochDay))
            put("derived_by", BUILDER)
            put("derived_version", "1")
            put("source_count", opened.size.toString())
            put("coverage_rows", refs.size.toString())
            put("complete", complete.toString())
            put("schema_versions", opened.map { it.file.metadata.schemaVersion ?: "?" }.distinct().joinToString(","))
            put("purpose", "telemetry-derived-day")
            if (anyEstimated) {
                put("estimated_rows", refs.count { r -> opened[r.src].estimated != null }.toString())
                put("time_estimate", "rows with null event_time_utc_ns carry $ESTIMATED_UTC = anchor_utc + (monotonic_us - anchor_mono) from a later SET_TIME of the same boot; drift not corrected (assume ±${TimeEstimate.DRIFT_PPM.toInt()} ppm)")
            }
        }

        // 5. Write, one row group per UTC hour.
        val target = store.dayFile(station, epochDay)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        var groups = 0
        tmp.outputStream().buffered().use { out ->
            val writer = ParquetWriter(out, columns, ParquetCodec.LZ4_RAW, meta, BUILDER, sortedBy = utcIndex.takeIf { it >= 0 })
            writer.begin()
            var start = 0
            while (start < refs.size) {
                val hour = Math.floorDiv(refs[start].utcNs, HOUR_NS)
                var end = start
                while (end < refs.size && Math.floorDiv(refs[end].utcNs, HOUR_NS) == hour) end++
                writer.rowGroup(gather(columns, decoded, estimatedBySource, refs.subList(start, end).map { it.src to it.row }))
                groups++
                start = end
            }
            writer.finish()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not move ${tmp.name} into place")
        }

        // 6. Manifest.
        val manifest = DailyManifest(
            station = station,
            day = DerivedStore.dayLabel(epochDay),
            sources = opened.map { s -> DailyManifest.Source(s.row.name, s.row.bytes, s.row.crc32, s.row.sha256, s.keep.size, s.row.estAnchorFile.takeIf { s.estimated != null }) },
            rows = refs.size,
            columns = columns.map { it.name },
            complete = complete,
            builder = BUILDER,
            builtAt = Instant.ofEpochSecond(nowEpochS).toString(),
            sourceSignature = sourceSignature,
        )
        val manifestFile = store.manifestFile(station, epochDay)
        val mTmp = File(manifestFile.parentFile, manifestFile.name + ".tmp")
        mTmp.writeText(JSON.encodeToString(manifest))
        if (!mTmp.renameTo(manifestFile)) { mTmp.delete(); throw java.io.IOException("could not move ${mTmp.name} into place") }
        // Belt and braces: the file we just wrote must pass the same structural check as a device file.
        check(ParquetVerify.hasMagic(target.readBytes())) { "derived file lost its magic" }
        return DailyBuildResult(target, manifestFile, refs.size, columns.size, groups, complete)
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
                    else -> {
                        val v = FloatArray(n); val valid = BooleanArray(n)
                        rows.forEachIndexed { k, (s, i) -> val col = decoded[s][c.name]; if (col != null && !col.isNull(i)) { v[k] = (col as ColumnValues.Float32).values[i]; valid[k] = true } }
                        ColumnValues.Float32(v, valid.takeIf { it.any { b -> !b } })
                    }
                }
            }
        }
    }

    companion object {
        const val BUILDER = "opensensor.space Android derived-v1"
        const val SOURCE_INDEX = "source_file_index"
        const val ESTIMATED_UTC = "event_time_utc_estimated_ns"
        const val HOUR_NS = 3_600L * 1_000_000_000L

        /** A day is "complete" only this long after it ended: the logger's last window has had time to close and sync. */
        const val COMPLETE_GRACE_S = 30 * 60L
        val COPIED_METADATA = setOf("board", "dictionary_version", "dictionary_uri", "dictionary_sha256", "device_id", "pms_status", "light_status", "proximity_status", "magnetic_units", "unavailable", "sample_interval_ms", "clock", "time_semantics")
        private val JSON = Json { prettyPrint = true }
    }
}
