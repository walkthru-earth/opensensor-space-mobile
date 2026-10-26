@file:OptIn(ExperimentalForeignApi::class)

package space.opensensor.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.ENOTSUP
import platform.posix.ENOTTY
import platform.posix.F_FULLFSYNC
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.RENAME_EXCL
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.renamex_np

/**
 * The system calls [IosAtomicFiles] makes, so a test can fail one. Each returns what the call
 * returns, and [errno] the error of the last failed one.
 */
internal interface PosixCalls {
    fun open(path: String, flags: Int, mode: Int = 0): Int
    fun write(fd: Int, bytes: ByteArray, offset: Int, count: Int): Long
    fun fullFsync(fd: Int): Int
    fun fsync(fd: Int): Int
    fun close(fd: Int): Int
    fun renameExclusive(from: String, to: String): Int
    fun rename(from: String, to: String): Int
    fun errno(): Int

    object System : PosixCalls {
        override fun open(path: String, flags: Int, mode: Int): Int = platform.posix.open(path, flags, mode.convert<UShort>())
        override fun write(fd: Int, bytes: ByteArray, offset: Int, count: Int): Long =
            bytes.usePinned { platform.posix.write(fd, it.addressOf(offset), count.convert()) }.convert()
        override fun fullFsync(fd: Int): Int = fcntl(fd, F_FULLFSYNC)
        override fun fsync(fd: Int): Int = platform.posix.fsync(fd)
        override fun close(fd: Int): Int = platform.posix.close(fd)
        override fun renameExclusive(from: String, to: String): Int = renamex_np(from, to, RENAME_EXCL.convert())
        override fun rename(from: String, to: String): Int = platform.posix.rename(from, to)
        override fun errno(): Int = errno
    }
}

/**
 * [AtomicFiles] for iOS. A Durable file is flushed with `fcntl(F_FULLFSYNC)`, which Apple documents
 * as the only call that empties the drive cache, falling back to `fsync` only where the volume does
 * not support it. The no-replace commit is `renamex_np(RENAME_EXCL)`, one atomic system call that
 * fails with `EEXIST` when the destination exists. Every result, `close` included, is checked.
 *
 * @param tempDir the [StorageRole.Temp] directory, on the volume of every target. It is created
 * with the archive protection class and excluded from backups.
 */
class IosAtomicFiles internal constructor(private val tempDir: String, private val posix: PosixCalls) : AtomicFiles {
    constructor(tempDir: String) : this(tempDir, PosixCalls.System)

    private var prepared = false

    override fun writeAtomically(target: String, bytes: ByteArray, cls: StorageClass) = writeThenCommit(target, bytes, cls, replace = true)

    override fun createNoReplace(target: String, bytes: ByteArray, cls: StorageClass) = writeThenCommit(target, bytes, cls, replace = false)

    private fun writeThenCommit(target: String, bytes: ByteArray, cls: StorageClass, replace: Boolean) {
        val temp = createTemp()
        try {
            val fd = posix.open(temp, O_WRONLY or O_CLOEXEC)
            if (fd < 0) fail("cannot open temp file")
            withFd(fd) {
                var done = 0
                while (done < bytes.size) {
                    val n = posix.write(fd, bytes, done, bytes.size - done)
                    if (n < 0 && posix.errno() == EINTR) continue
                    if (n <= 0) fail("cannot write temp file")
                    done += n.toInt()
                }
                sync(fd, cls)
            }
            rename(temp, target, cls, replace)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(temp, null)
        }
    }

    override fun commitNoReplace(from: String, to: String, cls: StorageClass) {
        syncFile(from, cls)
        rename(from, to, cls, replace = false)
    }

    override fun commitReplace(from: String, to: String, cls: StorageClass) {
        syncFile(from, cls)
        rename(from, to, cls, replace = true)
    }

    override fun createTemp(suffix: String): String {
        prepare()
        val path = "$tempDir/${AtomicFiles.TEMP_PREFIX}${NSUUID().UUIDString()}$suffix"
        val fd = posix.open(path, O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC, FILE_MODE)
        if (fd < 0) fail("cannot create temp file")
        if (posix.close(fd) != 0) fail("cannot close temp file")
        return path
    }

    override fun syncFile(path: String, cls: StorageClass) {
        val fd = posix.open(path, O_RDONLY or O_CLOEXEC)
        if (fd < 0) fail("cannot open file to sync")
        withFd(fd) { sync(fd, cls) }
    }

    override fun syncDir(dir: String) = syncDir(dir, StorageClass.Durable)

    private fun syncDir(dir: String, cls: StorageClass) {
        val fd = posix.open(dir, O_RDONLY or O_CLOEXEC)
        if (fd < 0) fail("cannot open directory to sync")
        withFd(fd) { sync(fd, cls) }
    }

    private fun rename(from: String, to: String, cls: StorageClass, replace: Boolean) {
        val parent = to.substringBeforeLast('/')
        // A directory created here is durable only once its own entry is synced, up to the first one that existed.
        val fm = NSFileManager.defaultManager
        val existing = generateSequence(parent) { dir -> dir.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } }
            .firstOrNull { fm.fileExistsAtPath(it) } ?: parent
        mkdirs(parent)
        val result = if (replace) posix.rename(from, to) else posix.renameExclusive(from, to)
        if (result != 0) {
            if (!replace && posix.errno() == EEXIST) throw AlreadyCommitted()
            fail("cannot rename into place")
        }
        var dir = parent
        while (true) {
            syncDir(dir, cls)
            if (dir == existing || '/' !in dir) break
            dir = dir.substringBeforeLast('/')
        }
    }

    /**
     * `F_FULLFSYNC` for Durable data. Only a volume that does not support it (`ENOTSUP`, `EINVAL`,
     * `ENOTTY`) falls back to `fsync`. Any other failure is a storage error.
     */
    private fun sync(fd: Int, cls: StorageClass) {
        if (cls == StorageClass.Durable) {
            if (posix.fullFsync(fd) == 0) return
            val error = posix.errno()
            if (error != ENOTSUP && error != EINVAL && error != ENOTTY) fail("cannot flush to storage")
        }
        if (posix.fsync(fd) != 0) fail("cannot sync to storage")
    }

    /** Runs [block], then closes [fd]. A failed close is an error unless [block] already threw. */
    private inline fun withFd(fd: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            posix.close(fd)
            throw e
        }
        if (posix.close(fd) != 0) fail("cannot close file")
    }

    private fun prepare() {
        if (prepared) return
        mkdirs(tempDir)
        NSURL.fileURLWithPath(tempDir, true).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        prepared = true
    }

    private fun mkdirs(dir: String) {
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(dir)) return
        if (!fm.createDirectoryAtPath(dir, true, mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), null)) {
            fail("cannot create directory")
        }
    }

    private fun fail(step: String): Nothing = throw StorageException("$step (errno ${posix.errno()})")

    private companion object {
        /** rw for the app only. */
        const val FILE_MODE = 0x180
    }
}
