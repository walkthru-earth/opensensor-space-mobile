package space.opensensor.storage

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * [AtomicFiles] for Android and the host JVM. Every commit takes one process-wide lock, checks the
 * destination without following links and renames with `ATOMIC_MOVE`. A hard link would make the
 * no-replace rule hold without the lock, but Android's SELinux policy forbids `link` for every
 * untrusted app (`app_neverallows.te`), and only this process writes these directories. Syncs use
 * `FileChannel.force`, which is `fsync` on Linux and on a directory opened for reading.
 *
 * @param tempDir the [StorageRole.Temp] directory, on the same volume as every target.
 */
class JvmAtomicFiles internal constructor(
    private val tempDir: File,
    /** Test seam, told each directory after it is synced. */
    private val dirSynced: (String) -> Unit,
) : AtomicFiles {
    constructor(tempDir: File) : this(tempDir, {})

    override fun writeAtomically(target: String, bytes: ByteArray, cls: StorageClass) = writeThenCommit(target, bytes, cls, replace = true)

    override fun createNoReplace(target: String, bytes: ByteArray, cls: StorageClass) = writeThenCommit(target, bytes, cls, replace = false)

    private fun writeThenCommit(target: String, bytes: ByteArray, cls: StorageClass, replace: Boolean) {
        val temp = File(createTemp())
        try {
            write(temp) { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            commit(temp.path, target, cls, replace)
        } finally {
            temp.delete()
        }
    }

    override fun commitNoReplace(from: String, to: String, cls: StorageClass) = commit(from, to, cls, replace = false)

    override fun commitReplace(from: String, to: String, cls: StorageClass) = commit(from, to, cls, replace = true)

    override fun createTemp(suffix: String): String {
        io("cannot create temp directory") { Files.createDirectories(tempDir.toPath()) }
        val file = File(tempDir, AtomicFiles.TEMP_PREFIX + UUID.randomUUID() + suffix)
        io("cannot create temp file") { Files.createFile(file.toPath()) }
        return file.path
    }

    override fun syncFile(path: String, cls: StorageClass) = io("cannot sync file") {
        FileChannel.open(File(path).toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun syncDir(dir: String) = io("cannot sync directory") {
        FileChannel.open(File(dir).toPath(), StandardOpenOption.READ).use { it.force(true) }
        dirSynced(dir)
    }

    private fun commit(from: String, to: String, cls: StorageClass, replace: Boolean) {
        val source = File(from).toPath()
        val target = File(to).toPath()
        syncFile(from, cls)
        val parent = checkNotNull(target.parent) { "commit target has no parent" }
        // A directory created here is durable only once its own entry is synced, up to the first one that existed.
        val existing = generateSequence(parent) { it.parent }.first { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) || it.parent == null }
        io("cannot create destination directory") { Files.createDirectories(parent) }
        synchronized(LOCK) {
            if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw AlreadyCommitted()
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileAlreadyExistsException) {
                if (!replace) throw AlreadyCommitted()
                throw StorageException("cannot rename into place", e)
            } catch (e: IOException) {
                throw StorageException("cannot rename into place", e)
            }
        }
        for (dir in generateSequence(parent) { it.parent }) {
            syncDir(dir.toString())
            if (dir == existing) break
        }
    }

    private inline fun write(file: File, block: (FileChannel) -> Unit) = io("cannot write temp file") {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            block(channel)
            channel.force(true)
        }
    }

    private inline fun <T> io(step: String, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: IOException) {
        // The class only: an IOException message from java.nio carries the path, and with it the thing.
        throw StorageException("$step (${e::class.simpleName})", e)
    }

    private companion object {
        /** One lock for every instance, so the check and the rename of two writers never interleave. */
        val LOCK = Any()
    }
}
