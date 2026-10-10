@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package earth.walkthru.aqsync.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.posix.EIO
import platform.posix.ENOTSUP
import platform.posix.memcpy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosAtomicFilesTest : AtomicFilesContract() {
    override val root: String = NSTemporaryDirectory().trimEnd('/') + "/atomic-files-" + NSUUID().UUIDString()

    override fun newFiles(tempDir: String): AtomicFiles = IosAtomicFiles(tempDir)
    override fun newTree(): StorageTree = IosStorageTree()

    override fun writeRaw(path: String, bytes: ByteArray) {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(fm.createFileAtPath(path, data, null))
    }

    override fun read(path: String): ByteArray? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** The system calls, with one of them failing on demand. */
    private class FailingPosix : PosixCalls by PosixCalls.System {
        var fullFsyncErrno: Int? = null
        var fsyncFails = false
        var closeFails = false
        var fullFsyncCalls = 0
        var fsyncCalls = 0
        private var lastErrno = 0

        override fun fullFsync(fd: Int): Int {
            fullFsyncCalls++
            val error = fullFsyncErrno ?: return PosixCalls.System.fullFsync(fd)
            lastErrno = error
            return -1
        }

        override fun fsync(fd: Int): Int {
            fsyncCalls++
            if (!fsyncFails) return PosixCalls.System.fsync(fd)
            lastErrno = EIO
            return -1
        }

        override fun close(fd: Int): Int {
            val result = PosixCalls.System.close(fd)
            if (!closeFails) return result
            lastErrno = EIO
            return -1
        }

        override fun errno(): Int = if (lastErrno != 0) lastErrno else PosixCalls.System.errno()
    }

    @Test
    fun aFailingFullFsyncIsAStorageErrorAndNothingIsCommitted() {
        val posix = FailingPosix()
        val files = IosAtomicFiles("$root/tmp", posix)
        val partial = files.createTemp()
        writeRaw(partial, "downloaded".encodeToByteArray())
        posix.fullFsyncErrno = EIO

        assertFailsWith<StorageException> { files.commitNoReplace(partial, "$root/archive/a.parquet", StorageClass.Durable) }
        assertFailsWith<StorageException> { files.writeAtomically("$root/receipt.json", ByteArray(3), StorageClass.Durable) }

        assertEquals(null, read("$root/archive/a.parquet"))
        assertEquals(null, read("$root/receipt.json"))
        assertEquals(0, posix.fsyncCalls)
    }

    @Test
    fun aVolumeWithoutFullFsyncFallsBackToFsync() {
        val posix = FailingPosix().apply { fullFsyncErrno = ENOTSUP }
        val files = IosAtomicFiles("$root/tmp", posix)

        files.writeAtomically("$root/receipt.json", "ok".encodeToByteArray(), StorageClass.Durable)

        assertContentEquals("ok".encodeToByteArray(), read("$root/receipt.json"))
        assertEquals(posix.fullFsyncCalls, posix.fsyncCalls)
        posix.fsyncFails = true
        assertFailsWith<StorageException> { files.writeAtomically("$root/receipt.json", "no".encodeToByteArray(), StorageClass.Durable) }
        assertContentEquals("ok".encodeToByteArray(), read("$root/receipt.json"))
    }

    @Test
    fun aFailingCloseIsAStorageError() {
        val posix = FailingPosix()
        val files = IosAtomicFiles("$root/tmp", posix)
        val partial = files.createTemp()
        posix.closeFails = true

        assertFailsWith<StorageException> { files.writeAtomically("$root/receipt.json", ByteArray(1), StorageClass.Rebuildable) }
        assertFailsWith<StorageException> { files.commitNoReplace(partial, "$root/archive/a.parquet", StorageClass.Durable) }
        assertEquals(null, read("$root/receipt.json"))
        assertEquals(null, read("$root/archive/a.parquet"))
    }

    @Test
    fun commitRefusesAnExistingFinalWithFullFsync() {
        val files = IosAtomicFiles("$root/tmp")
        val partial = files.createTemp()
        writeRaw("$root/archive/a.parquet", "first".encodeToByteArray())

        assertFailsWith<AlreadyCommitted> { files.commitNoReplace(partial, "$root/archive/a.parquet", StorageClass.Durable) }
        assertContentEquals("first".encodeToByteArray(), read("$root/archive/a.parquet"))
    }
}
