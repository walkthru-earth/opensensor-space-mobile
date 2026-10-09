package earth.walkthru.aqsync.data.history

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.data.archive.toRecord
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where the derived layer lives, `filesDir/derived/v1/<station>/day=YYYY-MM-DD.parquet` + `.manifest.json` ([ArchiveLayout]). Never inside `archive/`. */
@Singleton
class DerivedStore(baseDir: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    private val base: File = baseDir
    val root: File = File(baseDir, ArchiveLayout.DERIVED)

    fun dayFile(station: String, epochDay: Long): File = File(base, ArchiveLayout.derivedDayPath(station, epochDay))
    fun manifestFile(station: String, epochDay: Long): File = File(base, ArchiveLayout.derivedManifestPath(station, epochDay))

    /** Every daily file on this phone, as (station, file). */
    fun listAll(): List<Pair<String, File>> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).flatMap { dir -> (dir.listFiles { f -> f.isFile && f.extension == "parquet" } ?: emptyArray()).map { dir.name to it } }

    companion object {
        fun dayLabel(epochDay: Long): String = ArchiveLayout.dayLabel(epochDay)
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

/** Android atomic-file adapter for the shared Android/iOS UTC-day writer. */
class DailyDerivedBuilder(private val store: DerivedStore, private val fileFor: (station: String, name: String) -> File) {
    fun build(
        station: String,
        epochDay: Long,
        sources: List<ArchivedFile>,
        sourceSignature: String,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long = System.currentTimeMillis() / 1000,
    ): DailyBuildResult? {
        val opened = sources.map { DailyParquetSource(it.toRecord(), ParquetFile.open(fileFor(it.station, it.name))) }
        val target = store.dayFile(station, epochDay)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "cannot create derived directory" }
        val tmp = File.createTempFile(".derived-", ".parquet", target.parentFile)
        val manifestFile = store.manifestFile(station, epochDay)
        val manifestTmp = File.createTempFile(".derived-", ".json", target.parentFile)
        try {
            val result = java.io.FileOutputStream(tmp).use { output ->
                val buffered = output.buffered()
                val sink = object : ParquetSink {
                    override fun write(bytes: ByteArray) { buffered.write(bytes) }
                    override fun flush() { buffered.flush(); output.fd.sync() }
                }
                DailyParquetBuilder.build(station, epochDay, opened, sink, anchors, nowEpochS)
            } ?: return null
            check(ParquetFile.open(tmp).numRows == result.rows.toLong()) { "derived row count mismatch" }
            val manifest = DailyManifest(
                station = station,
                day = DerivedStore.dayLabel(epochDay),
                sources = result.sources.map { s -> DailyManifest.Source(s.record.name, s.record.bytes, s.record.crc32, s.record.sha256, s.rowsUsed, s.timeAnchor) },
                rows = result.rows,
                columns = result.columns,
                complete = result.complete,
                builder = BUILDER,
                builtAt = Instant.ofEpochSecond(nowEpochS).toString(),
                sourceSignature = sourceSignature,
            )
            java.io.FileOutputStream(manifestTmp).use { output ->
                output.write(JSON.encodeToString(manifest).toByteArray())
                output.fd.sync()
            }
            // A crash between the two renames must leave a missing manifest, never a stale cache receipt.
            check(!manifestFile.exists() || manifestFile.delete()) { "cannot invalidate derived manifest" }
            check(tmp.renameTo(target)) { "cannot commit derived Parquet" }
            check(manifestTmp.renameTo(manifestFile)) { "cannot commit derived manifest" }
            return DailyBuildResult(target, manifestFile, result.rows, result.columns.size, result.rowGroups, result.complete)
        } finally {
            tmp.delete()
            manifestTmp.delete()
        }
    }

    companion object {
        const val BUILDER = DailyParquetBuilder.BUILDER
        const val SOURCE_INDEX = DailyParquetBuilder.SOURCE_INDEX
        const val ESTIMATED_UTC = DailyParquetBuilder.ESTIMATED_UTC
        const val HOUR_NS = DailyParquetBuilder.HOUR_NS
        const val COMPLETE_GRACE_S = DailyParquetBuilder.COMPLETE_GRACE_S
        private val JSON = Json { prettyPrint = true }
    }
}
