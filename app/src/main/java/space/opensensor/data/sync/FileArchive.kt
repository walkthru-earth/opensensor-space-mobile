package space.opensensor.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.PartialMeta
import space.opensensor.storage.AlreadyCommitted
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.JvmAtomicFiles
import space.opensensor.storage.StorageClass
import space.opensensor.storage.StorageException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's file I/O for the [ArchiveLayout] paths under `filesDir`. Every path, name check and the
 * resume decision come from [ArchiveLayout]. Files land in `archive/` only via [commit], an atomic
 * no-replace move after verification, synced to disk (#60).
 */
@Singleton
class FileArchive(
    baseDir: File,
    private val atomic: AtomicFiles = JvmAtomicFiles(File(baseDir, AtomicFiles.TEMP_DIR)),
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    private val base: File = baseDir
    val root: File = File(baseDir, ArchiveLayout.ARCHIVE)
    val partialRoot: File = File(baseDir, ArchiveLayout.PARTIAL)

    fun fileFor(thing: String, name: String): File = File(File(base, ArchiveLayout.thingDir(thing)), name)

    fun exists(thing: String, name: String): Boolean = fileFor(thing, name).isFile

    /** Every regular file under `archive/`, as (thing, relative name, file). */
    fun listAll(): List<Triple<String, String, File>> {
        val things = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return things.flatMap { thingDir ->
            thingDir.walkTopDown().filter { it.isFile && it.extension == "parquet" }.map { f ->
                Triple(thingDir.name, f.relativeTo(thingDir).path, f)
            }.toList()
        }
    }

    // ------------------------------------------------------------------ partials

    private fun partialFor(key: ArchiveKey) = File(base, ArchiveLayout.partialPath(key))
    private fun metaFor(key: ArchiveKey) = File(base, ArchiveLayout.partialMetaPath(key))

    /** Byte counts of resumable partials for [thing], keyed by Hive name, for the planner. */
    fun partialLengths(thing: String): Map<String, Long> {
        val dir = File(base, ArchiveLayout.partialThingDir(thing))
        if (!dir.isDirectory) return emptyMap()
        return dir.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            ArchiveLayout.nameOfPartial(f.relativeTo(dir).invariantSeparatorsPath)?.let { it to f.length() }
        }.toMap()
    }

    /**
     * Open (or resume) the partial for a file the device just `OPENED`. [ArchiveLayout.resumeFrom]
     * decides the offset. When it says 0, the partial and its meta are discarded and start again.
     *
     * @return the partial and the offset to continue from.
     */
    fun openPartial(key: ArchiveKey, meta: PartialMeta): Pair<PartialWriter, Long> {
        val file = partialFor(key)
        val metaFile = metaFor(key)
        file.parentFile?.mkdirs()
        val existing = file.takeIf { it.isFile }?.length()
        val stored = metaFile.takeIf { it.isFile }?.readText()
        val resumeFrom = ArchiveLayout.resumeFrom(existing, stored, meta)
        if (resumeFrom == 0L) {
            file.delete()
            metaFile.delete()
        }
        atomic.writeAtomically(metaFile.path, ArchiveLayout.encodeMeta(meta).encodeToByteArray(), StorageClass.Scratch)
        return PartialWriter(file) to resumeFrom
    }

    fun discardPartial(key: ArchiveKey) {
        partialFor(key).delete()
        metaFor(key).delete()
    }

    /**
     * Move a verified partial into the archive with [AtomicFiles.commitNoReplace]. The destination is
     * never observable half-written and never replaced.
     *
     * @throws AlreadyCommitted when the final already exists. The partial is left in place.
     */
    @Throws(StorageException::class)
    fun commit(key: ArchiveKey): File {
        val src = partialFor(key)
        val dst = File(base, ArchiveLayout.finalPath(key))
        atomic.commitNoReplace(src.path, dst.path, StorageClass.Durable)
        metaFor(key).delete()
        return dst
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
