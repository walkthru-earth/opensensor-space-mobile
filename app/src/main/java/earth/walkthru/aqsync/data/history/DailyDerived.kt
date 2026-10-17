package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.archive.toRecord
import earth.walkthru.aqsync.data.history.parquet.LocalRangeSource
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the derived layer lives, `cacheDir/derived/v1/<station>/day=YYYY-MM-DD.parquet` + `.manifest.json`
 * ([ArchiveLayout]). Never inside `archive/`. It is a cache the OS or the quota may clear, the derive
 * pass and [HistoryDeriver.ensureDailyFiles] rebuild it from the archive (#61).
 */
@Singleton
class DerivedStore(
    baseDir: File,
    /** Temps and commits of the derived layer, Rebuildable (#60). */
    val atomic: AtomicFiles = JvmAtomicFiles(File(baseDir, AtomicFiles.TEMP_DIR)),
) {
    /** [StorageRole.Derived] is `<base>/derived/v1`. Temps go through the app's [AtomicFiles]. */
    @Inject
    constructor(roots: StorageRoots, atomic: AtomicFiles) : this(baseOf(roots), atomic)

    private val base: File = baseDir
    val root: File = File(baseDir, ArchiveLayout.DERIVED)

    /** Receipt and target replacement share one short lock with opening a read descriptor. */
    internal val commitLock = Any()

    fun dayFile(station: String, epochDay: Long): File = File(base, ArchiveLayout.derivedDayPath(station, epochDay))
    fun manifestFile(station: String, epochDay: Long): File = File(base, ArchiveLayout.derivedManifestPath(station, epochDay))

    /** Every daily file on this phone, as (station, file). */
    fun listAll(): List<Pair<String, File>> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).flatMap { dir -> (dir.listFiles { f -> f.isFile && f.extension == "parquet" } ?: emptyArray()).map { dir.name to it } }

    companion object {
        fun dayLabel(epochDay: Long): String = ArchiveLayout.dayLabel(epochDay)

        private fun baseOf(roots: StorageRoots): File {
            val root = roots.rootOf(StorageRole.Derived)
            check(root.endsWith("/${ArchiveLayout.DERIVED}")) { "derived root must end in ${ArchiveLayout.DERIVED}" }
            return File(root.removeSuffix("/${ArchiveLayout.DERIVED}"))
        }
    }
}

/**
 * The Day view's derived files on Android (#63). A day file is handed out only when `derived_day`
 * records it under the signature of exactly the sources the view would read, so a late file or a new
 * time estimate sends the view back to the raw files until the next derive pass.
 */
@Singleton
class RoomDerivedDayFiles @Inject constructor(private val store: DerivedStore, private val dayDao: DerivedDayDao) : DerivedDayFiles {
    override suspend fun open(station: String, epochDay: Long, sources: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>): LocalRangeSource? {
        val day = dayDao.get(station, epochDay) ?: return null
        val signature = HistoryDeriver.signatureOf(sources)
        if (day.dailyFile == null || day.sourceSignature != signature) return null
        return synchronized(store.commitLock) {
            val manifest = store.manifestFile(station, epochDay)
            val receipt = try {
                if (!manifest.isFile || manifest.length() > 1_048_576) return@synchronized null
                Json.decodeFromString<DailyManifest>(manifest.readText())
            } catch (_: Exception) {
                return@synchronized null
            }
            // A file from an older builder may store UTC in another unit or layout, so it is rebuilt.
            if (receipt.builder != DailyParquetBuilder.BUILDER || receipt.sourceSignature != signature || receipt.station != station || receipt.day != DerivedStore.dayLabel(epochDay)) return@synchronized null
            val file = store.dayFile(station, epochDay)
            // LocalRangeSource opens its channel in the constructor. A later replace cannot change
            // this descriptor's bytes after the lock is released.
            if (file.isFile) LocalRangeSource(file) else null
        }
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

/** What [DailyDerivedBuilder.pass] gives back: the shared pass and, when it was written, the daily file. */
class DailyDerivedPass(val pass: DayPassResult, val daily: DailyBuildResult?)

/** Android atomic-file adapter for the shared Android/iOS UTC-day writer. */
class DailyDerivedBuilder(private val store: DerivedStore, private val fileFor: (station: String, name: String) -> File) {
    /** The daily file alone, as the export's rebuild of an evicted file needs it. Throws when a source fails. */
    fun build(
        station: String,
        epochDay: Long,
        sources: List<ArchivedFile>,
        sourceSignature: String,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long = System.currentTimeMillis() / 1000,
    ): DailyBuildResult? {
        val run = pass(station, epochDay, sources, sourceSignature, anchors, nowEpochS, tiers = false)
        run.pass.dailyError?.let { throw it }
        return run.daily
    }

    /**
     * One decode of the day's [sources] for the tiers and the daily file (#63). The daily file is
     * committed only when every source read, otherwise [DailyDerivedPass.daily] is null and
     * [DayPassResult.dailyError] says why.
     */
    fun pass(
        station: String,
        epochDay: Long,
        sources: List<ArchivedFile>,
        sourceSignature: String,
        anchors: Map<String, TimeEstimate.Anchor> = emptyMap(),
        nowEpochS: Long = System.currentTimeMillis() / 1000,
        tiers: Boolean = true,
    ): DailyDerivedPass {
        // The pass opens and closes one source at a time (#61).
        val daily = sources.map { source -> DailyParquetSource(source.toRecord()) { ParquetFile.open(fileFor(source.station, source.name)) } }
        val target = store.dayFile(station, epochDay)
        val atomic = store.atomic
        val manifestFile = store.manifestFile(station, epochDay)
        var tmp: File? = null
        var decoded: DayPassResult? = null
        try {
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "cannot create derived directory" }
            val temporary = File(atomic.createTemp(".parquet"))
            tmp = temporary
            var written = 0L
            val pass = java.io.FileOutputStream(temporary).use { output ->
                val buffered = output.buffered()
                val sink = object : ParquetSink {
                    override fun write(bytes: ByteArray) {
                        buffered.write(bytes)
                        written += bytes.size
                    }
                    override fun flush() { buffered.flush(); output.fd.sync() }
                }
                DayPass.run(station, epochDay, daily, sink, anchors, nowEpochS, tiers).also { decoded = it }
            }
            val result = pass.daily ?: return DailyDerivedPass(pass, null)
            // The writer counted the rows, the synced length says every byte reached the file. No footer re-parse.
            check(temporary.length() == written) { "derived file is short" }
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
            // A crash between the two commits must leave a missing manifest, never a stale cache receipt.
            synchronized(store.commitLock) {
                check(!manifestFile.exists() || manifestFile.delete()) { "cannot invalidate derived manifest" }
                atomic.commitReplace(temporary.path, target.path, StorageClass.Rebuildable)
                atomic.writeAtomically(manifestFile.path, JSON.encodeToString(manifest).toByteArray(), StorageClass.Rebuildable)
            }
            return DailyDerivedPass(pass, DailyBuildResult(target, manifestFile, result.rows, result.columns.size, result.rowGroups, result.complete))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Decode without a sink only if cache setup failed before the pass. A later write or commit
            // failure keeps the buckets from that same pass, so no source is opened twice.
            val pass = decoded ?: DayPass.run(station, epochDay, daily, null, anchors, nowEpochS, tiers)
            return DailyDerivedPass(DayPassResult(pass.buckets, null, pass.rowsRead, pass.failedSources, e), null)
        } finally {
            tmp?.delete()
        }
    }

    companion object {
        const val BUILDER = DailyParquetBuilder.BUILDER
        const val SOURCE_INDEX = DailyParquetBuilder.SOURCE_INDEX
        const val ESTIMATED_UTC = DailyParquetBuilder.ESTIMATED_UTC
        const val HOUR_NS = DailyParquetBuilder.HOUR_NS
        const val COMPLETE_GRACE_S = DailyParquetBuilder.COMPLETE_GRACE_S

        /** Compact, a cache receipt read by code. */
        private val JSON = Json
    }
}
