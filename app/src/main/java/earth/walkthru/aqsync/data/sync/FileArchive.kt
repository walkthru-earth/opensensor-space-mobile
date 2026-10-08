package earth.walkthru.aqsync.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.protocol.SafeName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [commit], i.e. an atomic rename after verification.
 */
@Singleton
class FileArchive(baseDir: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    val root: File = File(baseDir, "archive")
    val partialRoot: File = File(baseDir, "archive-partial")

    private class WriterSlot(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val writers = HashMap<Pair<String, String>, WriterSlot>()

    /** One writer per archive identity, including waiters; different files can transfer together. */
    suspend fun <T> withWriterLease(station: String, name: String, block: suspend () -> T): T {
        safeStation(station)
        require(SafeName.isValid(name)) { "unsafe archive name" }
        val key = station to name
        val slot = synchronized(writers) {
            writers.getOrPut(key) { WriterSlot() }.also { it.users++ }
        }
        try {
            return slot.mutex.withLock { block() }
        } finally {
            synchronized(writers) {
                slot.users--
                if (slot.users == 0) writers.remove(key)
            }
        }
    }

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
        meta.writeText(expectedMeta)
        return PartialWriter(file) to resumeFrom
    }

    fun discardPartial(station: String, name: String) {
        partialFor(station, name).delete()
        metaFor(station, name).delete()
    }

    /**
     * Move a verified partial into the archive. Same filesystem, so `renameTo` is atomic; the
     * destination is never observable half-written.
     */
    @Throws(IOException::class)
    fun commit(station: String, name: String): File {
        val src = partialFor(station, name)
        val dst = fileFor(station, name)
        dst.parentFile?.mkdirs()
        if (dst.exists()) throw IOException("refusing to replace archived file ${dst.path}")
        if (!src.renameTo(dst)) throw IOException("rename ${src.path} → ${dst.path} failed")
        metaFor(station, name).delete()
        return dst
    }

    fun delete(station: String, name: String): Boolean = fileFor(station, name).delete()

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

        override fun close() {
            runCatching { raf.fd.sync() }
            raf.close()
        }
    }
}
