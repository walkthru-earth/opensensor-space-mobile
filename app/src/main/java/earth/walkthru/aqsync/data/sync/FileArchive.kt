package earth.walkthru.aqsync.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private on-disk layout for synced Parquet files.
 *
 * ```
 * filesDir/archive/<station>/<relative hive name>          verified, immutable copies
 * filesDir/archive-partial/<station>/<name>.partial         in-progress download, absolute offsets
 * filesDir/archive-partial/<station>/<name>.partial.meta    "<size> <crc32>" from the OPENED frame
 * ```
 *
 * The Hive path is kept verbatim below the station directory so the archive is a drop-in mirror
 * of the microSD card and of `tools/export_parquet.py` output. Files land in `archive/` only via
 * [commit], an atomic no-replace move after verification, synced to disk (#60).
 */
@Singleton
class FileArchive(
    baseDir: File,
    private val atomic: AtomicFiles = JvmAtomicFiles(File(baseDir, AtomicFiles.TEMP_DIR)),
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    val root: File = File(baseDir, "archive")
    val partialRoot: File = File(baseDir, "archive-partial")

    fun fileFor(station: String, name: String): File = File(File(root, safeStation(station)), name)

    fun exists(station: String, name: String): Boolean = fileFor(station, name).isFile

    /** Every regular file under `archive/`, as (station, relative name, file). */
    fun listAll(): List<Triple<String, String, File>> {
        val stations = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return stations.flatMap { stationDir ->
            stationDir.walkTopDown().filter { it.isFile && it.extension == "parquet" }.map { f ->
                Triple(stationDir.name, f.relativeTo(stationDir).path, f)
            }.toList()
        }
    }

    // ------------------------------------------------------------------ partials

    private fun partialFor(station: String, name: String) = File(File(partialRoot, safeStation(station)), "$name.partial")
    private fun metaFor(station: String, name: String) = File(File(partialRoot, safeStation(station)), "$name.partial.meta")

    /** Byte counts of resumable partials for [station], keyed by relative name, for the planner. */
    fun partialLengths(station: String): Map<String, Long> {
        val dir = File(partialRoot, safeStation(station))
        if (!dir.isDirectory) return emptyMap()
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".partial") }.associate { f ->
            f.relativeTo(dir).path.removeSuffix(".partial") to f.length()
        }
    }

    /**
     * Open (or resume) the partial for a file the device just `OPENED`. If a partial exists but was
     * written against a different `size`/`crc32`, the file on the card is not the one we started
     * (impossible for an immutable file, but the contract says check), so it is discarded.
     *
     * @return the partial and the offset to continue from.
     */
    fun openPartial(station: String, name: String, size: Long, crc32: Long): Pair<PartialWriter, Long> {
        val file = partialFor(station, name)
        val meta = metaFor(station, name)
        file.parentFile?.mkdirs()
        val expectedMeta = "$size $crc32"
        var resumeFrom = 0L
        if (file.isFile && meta.isFile && meta.readText().trim() == expectedMeta && file.length() <= size) {
            resumeFrom = file.length()
        } else {
            file.delete()
            meta.delete()
        }
        atomic.writeAtomically(meta.path, expectedMeta.encodeToByteArray(), StorageClass.Scratch)
        return PartialWriter(file) to resumeFrom
    }

    fun discardPartial(station: String, name: String) {
        partialFor(station, name).delete()
        metaFor(station, name).delete()
    }

    /**
     * Move a verified partial into the archive with [AtomicFiles.commitNoReplace]. The destination is
     * never observable half-written and never replaced.
     *
     * @throws AlreadyCommitted when the final already exists. The partial is left in place.
     */
    @Throws(StorageException::class)
    fun commit(station: String, name: String): File {
        val src = partialFor(station, name)
        val dst = fileFor(station, name)
        atomic.commitNoReplace(src.path, dst.path, StorageClass.Durable)
        metaFor(station, name).delete()
        return dst
    }

    /** Station UUIDs are `[0-9a-f-]`; anything else is refused rather than used as a path. */
    private fun safeStation(station: String): String {
        require(station !in setOf(".", "..") && station.isNotEmpty() && station.matches(Regex("[0-9A-Za-z._-]+"))) { "unsafe station id: $station" }
        return station
    }

    /** Writes CHUNK payloads at their absolute offsets. */
    class PartialWriter(val file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")

        fun writeAt(offset: Long, payload: ByteArray) {
            raf.seek(offset)
            raf.write(payload)
        }

        fun length(): Long = raf.length()

        fun sync() = raf.fd.sync()

        /** Syncs, then closes. Either failure is raised after the file is closed, never swallowed. */
        override fun close() {
            val failure = runCatching { raf.fd.sync() }.exceptionOrNull()
            try {
                raf.close()
            } catch (e: IOException) {
                failure?.addSuppressed(e) ?: throw e
            }
            if (failure != null) throw IOException("partial sync failed", failure)
        }
    }
}
