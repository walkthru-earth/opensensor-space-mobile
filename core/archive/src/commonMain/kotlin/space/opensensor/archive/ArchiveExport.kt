package space.opensensor.archive

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.storage.StorageException
import space.opensensor.storage.isOutOfSpace
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

/**
 * An archived file that is no longer there. A reader throws it only after it checked that the path is
 * gone, so a zip can leave that file out and still fail on any other error (#168).
 */
class ArchiveFileMissingException(message: String) : IllegalStateException(message)

/**
 * True when [error] says the file an export tried to open no longer exists. Never true for another
 * failure, such as a denied permission, an IO error or too many open files.
 */
internal expect fun isMissingFile(error: Throwable): Boolean

/**
 * A file staged for the share sheet or the save picker, inside one [ExportRun]. [skipped] counts the
 * listed files a zip left out because they were gone by then ([ArchiveExport.writeZip]).
 */
class StagedFile(val path: String, val name: String, val mime: String, val run: ExportRun, val skipped: Int = 0)

/**
 * The temporary space exports are staged in. Each export gets its own run directory (B7), and runs
 * coexist, so a new export never removes a file an earlier share still offers or another controller
 * is still staging. A run leaves through its owner ([ExportRun.delete] after a close signal) or, once
 * released, through the age sweep of [ExportRuns]. Android uses `cacheDir/export`, which its
 * `FileProvider` serves. iOS uses a folder in the app's temporary directory.
 */
interface ExportSpace {
    /**
     * Creates a new, empty run under a lease, after sweeping the released runs older than
     * [ExportRuns.MAX_AGE_MS]. Runs that are leased or younger stay.
     */
    fun newRun(): ExportRun

    /** Bytes a new run may still use, or null when the platform cannot tell (#61). */
    fun freeBytes(): Long? = null
}

/** One export's directory, leased from [ExportSpace.newRun] until [release] or [delete]. */
interface ExportRun {
    /** Absolute path of [name] in this run. */
    fun path(name: String): String

    /** Creates [name] in this run for writing. */
    fun create(name: String): ExportSink

    /**
     * Hands the run over to a reader that gives no close signal. The run stays on disk, its time is
     * refreshed and its lease ends, so the age sweep removes it [ExportRuns.MAX_AGE_MS] later. Safe to
     * call twice.
     */
    fun release()

    /** Removes the run and everything in it, and ends its lease. Safe to call twice. */
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
     * The given verified [records] as `<station>/<Hive name>`, sorted by that path, the layout of the
     * old Android zip. Only these exact keys go in, so a station's zip never carries another station's
     * files or a final the index never verified (#156). The whole-phone zip passes every record. A
     * file's time is when it was verified ([ArchiveRecord.syncedAtEpochMs]).
     */
    fun archiveEntries(store: ArchiveStore, records: List<ArchiveRecord>): List<ExportEntry> =
        records.distinctBy { it.key }.sortedBy { "${it.station}/${it.name}" }.map { r ->
            ExportEntry("${r.station}/${r.name}", r.syncedAtEpochMs) { store.reader(r.key) }
        }

    /** The given verified files, for a loose share. */
    fun recordEntries(store: ArchiveStore, records: List<ArchiveRecord>): List<ExportEntry> =
        records.sortedBy { it.name }.map { r -> ExportEntry("${r.station}/${r.name}", r.syncedAtEpochMs) { store.reader(r.key) } }

    /**
     * The daily export, `derived/v1/<station>/...` Parquet files and their manifests of [stations]
     * only, sorted. A derived file of any other station stays out, the same scope as the station's
     * archive zip (#156). Null when no daily Parquet file is left, so the screen can say so instead
     * of sharing an empty zip.
     */
    fun dailyEntries(derived: List<ExportEntry>, stations: Set<String>): List<ExportEntry>? {
        val kept = derived.filter { e ->
            (e.path.endsWith(".parquet") || e.path.endsWith(".manifest.json")) && derivedStation(e.path) in stations
        }
        if (kept.none { it.path.endsWith(".parquet") }) return null
        return kept.sortedBy { it.path }
    }

    /** The station directory of a `derived/v1/<station>/<file>` path, else null. */
    private fun derivedStation(path: String): String? {
        val rest = path.removePrefix("${ArchiveLayout.DERIVED}/").takeIf { it != path } ?: return null
        return rest.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
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

    /**
     * Writes [entries] as one STORED zip into [sink], the writer every zip export shares (#61). Each
     * entry is opened once and closed after its copy. The sink stays open for the caller to close.
     * [checkpoint] runs before each entry and each 64 KiB slice, so a caller can stop a long write
     * (a coroutine passes `ensureActive`). A full disk fails with [StorageException].
     *
     * An entry whose file is gone, such as a final the old format cleanup removed after the listing,
     * is left out and the zip still builds (#168). Returns how many were left out. When no Parquet file
     * is left to write, the first of those failures is thrown instead, so a zip never holds manifests
     * alone. Any other failure to open or read an entry still fails the zip.
     */
    fun writeZip(sink: ExportSink, entries: List<ExportEntry>, checkpoint: () -> Unit = {}): Int = mappingNoSpace {
        val zip = StoredZipWriter(sink, checkpoint = checkpoint)
        var skipped = 0
        var finals = 0
        var firstMissing: Exception? = null
        for (e in entries) {
            checkpoint()
            val reader = try {
                e.open()
            } catch (x: Exception) {
                if (!isMissingFile(x)) throw x
                skipped++
                firstMissing = firstMissing ?: x
                continue
            }
            reader.use { zip.add(e.path, it, e.modifiedEpochMs) }
            if (e.path.endsWith(".parquet")) finals++
        }
        firstMissing?.let { if (finals == 0) throw it }
        zip.finish()
        skipped
    }

    /** Rethrows an out of space failure as [StorageException], so the screen says the disk is full. */
    private inline fun <T> mappingNoSpace(block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: Exception) {
        if (isOutOfSpace(e)) throw StorageException("not enough free space for the export", e)
        throw e
    }

    /**
     * An upper bound of the zip [writeZip] writes for [entries]: the bytes, both headers with their
     * zip64 fields per entry, and the end records.
     */
    fun plannedZipBytes(entries: List<ExportEntry>): Long = entries.sumOf { e ->
        val name = e.path.encodeToByteArray().size.toLong()
        // An entry whose file is gone is left out of the zip, so it adds nothing here either.
        val size = try {
            e.open().use { it.size }
        } catch (x: Exception) {
            if (!isMissingFile(x)) throw x
            return@sumOf 0L
        }
        size + 2 * name + ZIP_ENTRY_OVERHEAD
    } + ZIP_END_OVERHEAD

    /**
     * Writes [entries] as one STORED zip named [name] into a new run. A space that reports fewer free
     * bytes than [plannedZipBytes] fails with [StorageException] before anything is written. The run
     * is deleted on failure. On success it stays leased, and the caller releases or deletes it.
     * [checkpoint] is passed to [writeZip], and the entries it left out are [StagedFile.skipped].
     */
    fun stageZip(space: ExportSpace, entries: List<ExportEntry>, name: String, checkpoint: () -> Unit = {}): StagedFile {
        require(entries.isNotEmpty()) { "nothing to export" }
        val run = space.newRun()
        try {
            val free = space.freeBytes()
            if (free != null && plannedZipBytes(entries) > free) throw StorageException("not enough free space for the export")
            val skipped = run.create(name).use { sink -> writeZip(sink, entries, checkpoint) }
            return StagedFile(run.path(name), name, ZIP_MIME, run, skipped)
        } catch (t: Throwable) {
            run.delete()
            throw t
        }
    }

    /**
     * Copies [entries] as loose Parquet files into a new run, one per entry under its file name. A
     * name seen twice (two stations) gets the station folder as a prefix. The run is deleted on failure,
     * and on success it stays leased until the caller releases or deletes it.
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
                mappingNoSpace { run.create(name).use { sink -> e.open().use { copy(it, sink) } } }
                StagedFile(run.path(name), name, PARQUET_MIME, run)
            }
        } catch (t: Throwable) {
            run.delete()
            throw t
        }
    }

    // Local header 30 + zip64 extra 20, central header 46 + zip64 extra 28.
    private const val ZIP_ENTRY_OVERHEAD = 124L

    // End of central directory 22, zip64 end record 56 and its locator 20.
    private const val ZIP_END_OVERHEAD = 98L

    private fun copy(reader: ByteReader, sink: ExportSink) {
        var at = 0L
        while (at < reader.size) {
            val n = minOf(64L * 1024, reader.size - at).toInt()
            sink.write(reader.read(at, n))
            at += n
        }
    }
}
