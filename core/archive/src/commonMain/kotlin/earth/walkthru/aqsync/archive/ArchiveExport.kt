package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteReader
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One file an export copies, under [path] in the zip or as the [name] of a loose copy. */
class ExportEntry(
    val path: String,
    val modifiedEpochMs: Long,
    /** Opens the bytes. Called once per copy, never to write. */
    val open: () -> ByteReader,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** A file staged for the share sheet or the save picker, inside one [ExportRun]. */
class StagedFile(val path: String, val name: String, val mime: String, val run: ExportRun)

/**
 * The temporary space exports are staged in. Each export gets its own run directory (B7), removed
 * when the sheet closes, and a new run first removes any run an earlier export left behind (the app
 * was killed, or the platform cannot say when its sheet closed). Android uses `cacheDir/export`,
 * which its `FileProvider` serves. iOS uses a folder in the app's temporary directory.
 */
interface ExportSpace {
    /** Removes every earlier run, then creates a new, empty one. */
    fun newRun(): ExportRun
}

/** One export's directory. */
interface ExportRun {
    /** Absolute path of [name] in this run. */
    fun path(name: String): String

    /** Creates [name] in this run for writing. */
    fun create(name: String): ExportSink

    /** Removes the run and everything in it. Safe to call twice. */
    fun delete()
}

/**
 * What an export copies, and how. Every byte comes from [ArchiveStore.reader] or from the derived
 * files the platform lists, read only. Nothing here can change, move or remove an archived file.
 */
object ArchiveExport {
    /** Parquet has a registered media type since 2024. */
    const val PARQUET_MIME: String = "application/vnd.apache.parquet"
    const val ZIP_MIME: String = "application/zip"

    /**
     * Every verified file as `<station>/<Hive name>`, sorted by that path, the layout of the old
     * Android zip. A file's time is when it was verified ([ArchiveRecord.syncedAtEpochMs]).
     */
    suspend fun archiveEntries(store: ArchiveStore, records: List<ArchiveRecord>, nowEpochMs: Long): List<ExportEntry> {
        val synced = records.associateBy({ it.key }, { it.syncedAtEpochMs })
        return store.listFinal().sortedBy { "${it.station}/${it.name}" }.map { key ->
            ExportEntry("${key.station}/${key.name}", synced[key] ?: nowEpochMs) { store.reader(key) }
        }
    }

    /** The given verified files, for a loose share. */
    fun recordEntries(store: ArchiveStore, records: List<ArchiveRecord>): List<ExportEntry> =
        records.sortedBy { it.name }.map { r -> ExportEntry("${r.station}/${r.name}", r.syncedAtEpochMs) { store.reader(r.key) } }

    /**
     * The daily export, `derived/v1/...` Parquet files and their manifests, sorted. Null when there is
     * no daily Parquet file at all, so the screen can say so instead of sharing an empty zip.
     */
    fun dailyEntries(derived: List<ExportEntry>): List<ExportEntry>? {
        val kept = derived.filter { it.path.endsWith(".parquet") || it.path.endsWith(".manifest.json") }
        if (kept.none { it.path.endsWith(".parquet") }) return null
        return kept.sortedBy { it.path }
    }

    /** `opensensor-space-archive-yyyyMMdd-HHmmss.zip` in UTC. */
    fun archiveZipName(epochMs: Long): String = "opensensor-space-archive-${stamp(epochMs)}.zip"

    /** `opensensor-space-daily-yyyyMMdd-HHmmss.zip` in UTC. */
    fun dailyZipName(epochMs: Long): String = "opensensor-space-daily-${stamp(epochMs)}.zip"

    private fun stamp(epochMs: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        fun p(v: Int, w: Int = 2) = v.toString().padStart(w, '0')
        return "${p(t.year, 4)}${p(t.month.ordinal + 1)}${p(t.day)}-${p(t.hour)}${p(t.minute)}${p(t.second)}"
    }

    /** Writes [entries] as one STORED zip named [name] into a new run. The run is deleted on failure. */
    fun stageZip(space: ExportSpace, entries: List<ExportEntry>, name: String): StagedFile {
        require(entries.isNotEmpty()) { "nothing to export" }
        val run = space.newRun()
        try {
            run.create(name).use { sink ->
                val zip = StoredZipWriter(sink)
                for (e in entries) zip.add(e.path, e.open(), e.modifiedEpochMs)
                zip.finish()
            }
            return StagedFile(run.path(name), name, ZIP_MIME, run)
        } catch (t: Throwable) {
            run.delete()
            throw t
        }
    }

    /**
     * Copies [entries] as loose Parquet files into a new run, one per entry under its file name. A
     * name seen twice (two stations) gets the station folder as a prefix. The run is deleted on failure.
     */
    fun stageCopies(space: ExportSpace, entries: List<ExportEntry>): List<StagedFile> {
        require(entries.isNotEmpty()) { "nothing to export" }
        val run = space.newRun()
        try {
            val repeated = entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            val used = HashSet<String>()
            return entries.map { e ->
                val base = if (e.name in repeated) e.path.substringBefore('/') + "-" + e.name else e.name
                var name = base
                var n = 1
                while (!used.add(name)) name = "${n++}-$base"
                run.create(name).use { sink -> copy(e.open(), sink) }
                StagedFile(run.path(name), name, PARQUET_MIME, run)
            }
        } catch (t: Throwable) {
            run.delete()
            throw t
        }
    }

    private fun copy(reader: ByteReader, sink: ExportSink) {
        var at = 0L
        while (at < reader.size) {
            val n = minOf(64L * 1024, reader.size - at).toInt()
            sink.write(reader.read(at, n))
            at += n
        }
    }
}
