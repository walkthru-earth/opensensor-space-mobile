@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.MappedDataReader
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.posix.O_RDWR
import platform.posix.SEEK_END
import platform.posix.errno
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.lseek
import platform.posix.open
import platform.posix.pwrite

/**
 * The iOS [ArchiveStore]. Verified copies stay where the iPhone app always kept them,
 * `Application Support/SensorArchive/<station>/<hive name>`, so archives collected by earlier builds
 * still read. In-progress downloads use the shared [ArchiveLayout] partial format, `<name>.partial`
 * and `<name>.partial.meta`, under `Application Support/archive-partial/<station>/`.
 *
 * Both roots carry the `completeUntilFirstUserAuthentication` protection class and are excluded from
 * backups. Symlinks are refused at every level below a root. An older build's partial beside the final
 * name, `<name>.<size>-<crc hex>.partial`, is adopted when its size and CRC match the device's OPENED
 * frame and removed otherwise. Nothing here asks a device to change a file.
 *
 * Commits and metas go through [AtomicFiles] (#60): a commit is one `renamex_np(RENAME_EXCL)` after
 * `F_FULLFSYNC`, so a final is never replaced and survives a power cut.
 *
 * @param finalRoot absolute path of the verified copies, [ArchiveLayout.ARCHIVE] maps to it.
 * @param partialRoot absolute path of the partials, [ArchiveLayout.PARTIAL] maps to it.
 */
class IosArchiveStore(
    val finalRoot: String,
    val partialRoot: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val atomic: AtomicFiles = IosAtomicFiles(tempDirBeside(finalRoot)),
) : ArchiveStore {
    private var prepared = false

    private fun prepare() {
        if (prepared) return
        IosFiles.prepareRoot(finalRoot)
        IosFiles.prepareRoot(partialRoot)
        prepared = true
    }

    private fun resolve(layoutPath: String): String = when {
        layoutPath.startsWith("${ArchiveLayout.PARTIAL}/") -> "$partialRoot/" + layoutPath.removePrefix("${ArchiveLayout.PARTIAL}/")
        layoutPath.startsWith("${ArchiveLayout.ARCHIVE}/") -> "$finalRoot/" + layoutPath.removePrefix("${ArchiveLayout.ARCHIVE}/")
        else -> throw IllegalArgumentException("not an archive path")
    }

    internal fun finalFile(key: ArchiveKey): String = resolve(ArchiveLayout.finalPath(key)).also { IosFiles.checkNoLinks(it, finalRoot) }
    private fun partialFile(key: ArchiveKey): String = resolve(ArchiveLayout.partialPath(key)).also { IosFiles.checkNoLinks(it, partialRoot) }
    private fun metaFile(key: ArchiveKey): String = resolve(ArchiveLayout.partialMetaPath(key)).also { IosFiles.checkNoLinks(it, partialRoot) }

    override suspend fun partialLengths(station: String): Map<String, Long> = withContext(io) {
        val dir = resolve(ArchiveLayout.partialStationDir(station))
        val lengths = LinkedHashMap<String, Long>()
        for (relative in IosFiles.walk(dir)) {
            val name = ArchiveLayout.nameOfPartial(relative) ?: continue
            val path = "$dir/$relative"
            if (IosFiles.kind(path) == Kind.FILE) lengths[name] = IosFiles.size(path)
        }
        val legacyDir = "$finalRoot/$station"
        for (relative in IosFiles.walk(legacyDir)) {
            val legacy = LegacyPartial.parse(relative) ?: continue
            val path = "$legacyDir/$relative"
            if (legacy.name !in lengths && IosFiles.kind(path) == Kind.FILE) lengths[legacy.name] = IosFiles.size(path)
        }
        lengths
    }

    override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = withContext(io) {
        prepare()
        val path = partialFile(key)
        val metaPath = metaFile(key)
        IosFiles.mkdirs(path.substringBeforeLast('/'))
        adoptLegacyPartial(key, meta, path, metaPath)
        val existing = when (IosFiles.kind(path)) {
            Kind.ABSENT -> null
            Kind.FILE -> IosFiles.size(path)
            else -> throw IllegalStateException("archive partial is not a file")
        }
        val stored = IosFiles.readSmall(metaPath, META_MAX_BYTES)?.decodeToString()?.let(ArchiveLayout::decodeMeta)
        val resumeFrom = if (existing != null && stored == meta && existing <= meta.size) existing else 0L
        if (existing == null) IosFiles.createEmpty(path)
        val fd = open(path, O_RDWR)
        check(fd >= 0) { "cannot open archive partial" }
        try {
            if (resumeFrom == 0L) check(ftruncate(fd, 0) == 0) { "cannot reset archive partial" }
            atomic.writeAtomically(metaPath, ArchiveLayout.encodeMeta(meta).encodeToByteArray(), StorageClass.Scratch)
        } catch (e: Throwable) {
            platform.posix.close(fd)
            throw e
        }
        PosixPartialWriter(fd, resumeFrom)
    }

    /**
     * Moves an older build's partial into the shared layout when it was written against the same
     * OPENED size and CRC, and removes the older partials of this name that can never complete.
     */
    private fun adoptLegacyPartial(key: ArchiveKey, meta: PartialMeta, path: String, metaPath: String) {
        val final = finalFile(key)
        val dir = final.substringBeforeLast('/')
        val base = final.substringAfterLast('/')
        if (IosFiles.kind(dir) != Kind.DIRECTORY) return
        val current = LegacyPartial.fileName(base, meta)
        for (entry in IosFiles.walk(dir)) {
            if ('/' in entry) continue
            val legacy = LegacyPartial.parse(entry) ?: continue
            if (legacy.name.substringAfterLast('/') != base) continue
            val legacyPath = "$dir/$entry"
            if (IosFiles.kind(legacyPath) != Kind.FILE) continue
            if (entry == current && IosFiles.kind(path) == Kind.ABSENT && IosFiles.size(legacyPath) <= meta.size) {
                atomic.writeAtomically(metaPath, ArchiveLayout.encodeMeta(meta).encodeToByteArray(), StorageClass.Scratch)
                IosFiles.move(legacyPath, path)
            } else {
                IosFiles.remove(legacyPath)
            }
        }
    }

    override suspend fun discardPartial(key: ArchiveKey) = withContext(io) {
        IosFiles.remove(partialFile(key))
        IosFiles.remove(metaFile(key))
        val final = finalFile(key)
        val dir = final.substringBeforeLast('/')
        val base = final.substringAfterLast('/')
        for (entry in IosFiles.walk(dir)) {
            if ('/' in entry) continue
            val legacy = LegacyPartial.parse(entry) ?: continue
            if (legacy.name.substringAfterLast('/') == base && IosFiles.kind("$dir/$entry") == Kind.FILE) IosFiles.remove("$dir/$entry")
        }
    }

    override suspend fun exists(key: ArchiveKey): Boolean = withContext(io) { IosFiles.kind(finalFile(key)) == Kind.FILE }

    override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
        val path = if (partial) partialFile(key) else finalFile(key)
        check(IosFiles.kind(path) == Kind.FILE) { "archive entry is not a file" }
        return MappedDataReader(path)
    }

    override suspend fun commit(key: ArchiveKey) = withContext(io) {
        val final = finalFile(key)
        val partial = partialFile(key)
        check(IosFiles.kind(partial) == Kind.FILE) { "no partial to commit" }
        // AtomicFiles creates the station and day directories and syncs each new level into its parent.
        // Throws AlreadyCommitted when the final is there, whatever it is. Nothing is replaced.
        atomic.commitNoReplace(partial, final, StorageClass.Durable)
        IosFiles.remove(metaFile(key))
    }

    override suspend fun listFinal(station: String?): List<ArchiveKey> = withContext(io) {
        val stations = if (station != null) {
            listOf(station).filter(ArchiveLayout::isSafeStation)
        } else {
            IosFiles.walk(finalRoot).filter { '/' !in it && ArchiveLayout.isSafeStation(it) }
        }
        stations.flatMap { s ->
            val dir = "$finalRoot/$s"
            if (IosFiles.kind(dir) != Kind.DIRECTORY) return@flatMap emptyList()
            IosFiles.walk(dir)
                .filter { ArchiveLayout.isArchiveName(it) && IosFiles.kind("$dir/$it") == Kind.FILE }
                .map { ArchiveKey(s, it) }
        }
    }

    companion object {
        private const val META_MAX_BYTES = 256L

        /** `Application Support/SensorArchive` for the copies, `Application Support/archive-partial` for downloads. */
        fun inApplicationSupport(): IosArchiveStore {
            val base = applicationSupport()
            return IosArchiveStore("$base/${ArchiveLayout.LEGACY_IOS_ROOT}", "$base/${ArchiveLayout.PARTIAL}")
        }

        /** The temp directory of [AtomicFiles] beside [root], in the same base directory and so on the same volume. */
        internal fun tempDirBeside(root: String): String = root.substringBeforeLast('/') + "/" + AtomicFiles.TEMP_DIR

        internal fun applicationSupport(): String {
            val url = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            return checkNotNull(url?.path) { "Application Support is unavailable" }
        }
    }
}

/** An older iOS build's partial, `<hive name>.<size>-<crc hex>.partial` beside the final name. */
internal data class LegacyPartial(val name: String, val size: Long, val crc32: Long) {
    companion object {
        private val PATTERN = Regex("""^(.+\.parquet)\.(\d{1,19})-([0-9a-f]{1,8})\.partial$""")

        fun parse(relative: String): LegacyPartial? {
            val match = PATTERN.matchEntire(relative) ?: return null
            val name = match.groupValues[1].takeIf(ArchiveLayout::isArchiveName) ?: return null
            val size = match.groupValues[2].toLongOrNull() ?: return null
            return LegacyPartial(name, size, match.groupValues[3].toLong(16))
        }

        /** The file name the older build gave this partial, without directories. */
        fun fileName(base: String, meta: PartialMeta): String = "$base.${meta.size}-${meta.crc32.toString(16)}.partial"
    }
}

/**
 * Writes at absolute offsets with `pwrite`. [sync] and [close] reach storage with `fsync`, a partial
 * being Scratch. [close] reports a failed `fsync` or `close` as a [StorageException] instead of
 * dropping it, so a partial that never reached the disk is never verified as if it had.
 */
private class PosixPartialWriter(private val fd: Int, override val resumeFrom: Long) : PartialWriter {
    private var closed = false

    override fun writeAt(offset: Long, payload: ByteArray) {
        check(!closed) { "partial closed" }
        var done = 0
        while (done < payload.size) {
            val n = payload.usePinned { pinned ->
                pwrite(fd, pinned.addressOf(done), (payload.size - done).convert(), (offset + done).convert())
            }
            check(n > 0) { "cannot write archive partial" }
            done += n.toInt()
        }
    }

    override fun length(): Long {
        check(!closed) { "partial closed" }
        return lseek(fd, 0, SEEK_END)
    }

    override fun sync() {
        check(!closed) { "partial closed" }
        if (fsync(fd) != 0) throw StorageException("cannot sync archive partial (errno $errno)")
    }

    override fun close() {
        if (closed) return
        closed = true
        val synced = fsync(fd) == 0
        val syncErrno = errno
        if (platform.posix.close(fd) != 0) throw StorageException("cannot close archive partial (errno $errno)")
        if (!synced) throw StorageException("cannot sync archive partial (errno $syncErrno)")
    }
}
