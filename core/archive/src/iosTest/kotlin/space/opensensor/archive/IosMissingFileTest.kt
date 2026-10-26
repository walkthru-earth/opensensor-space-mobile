@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import kotlinx.cinterop.convert
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.chmod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** On iOS, a zip leaves out a final that is gone and fails on any other open error (#168). */
class IosMissingFileTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/missing-file-test-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Unconfined)
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val locked = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        locked.forEach { chmod(it, "755".toInt(8).convert()) }
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun record(day: Int): ArchiveRecord {
        val name = "thing=$thing/year=2026/month=09/day=$day/data_0930_a_2-106-2.parquet"
        val path = "$finals/$thing/$name"
        IosFiles.mkdirs(path.substringBeforeLast('/'))
        IosFiles.writeAtomically(path, "PAR1 $day".encodeToByteArray())
        return ArchiveRecord(thing = thing, name = name, bytes = 6, crc32 = 1, sha256 = "x", syncedAtEpochMs = 1, sourceDevice = thing)
    }

    private fun pathOf(r: ArchiveRecord) = "$finals/$thing/${r.name}"

    private fun lock(path: String) {
        chmod(path, 0.convert())
        locked += path
    }

    @Test
    fun aFinalRemovedAfterTheListingIsLeftOut() {
        val kept = record(17)
        val gone = record(18)
        val entries = ArchiveExport.archiveEntries(store, listOf(kept, gone))
        IosFiles.remove(pathOf(gone))
        assertFailsWith<ArchiveFileMissingException> { store.reader(gone.key) }
        assertEquals(1, ArchiveExport.writeZip(MemorySink(), entries))
    }

    @Test
    fun aFinalThatCannotBeOpenedFailsTheExport() {
        val kept = record(17)
        val denied = record(18)
        lock(pathOf(denied))
        val entries = ArchiveExport.archiveEntries(store, listOf(kept, denied))
        val failure = assertFailsWith<Exception> { ArchiveExport.writeZip(MemorySink(), entries) }
        assertFalse(isMissingFile(failure))
    }

    @Test
    fun aFolderThatCannotBeSearchedIsNotAGoneFinal() {
        val kept = record(17)
        val hidden = record(18)
        val dir = pathOf(hidden).substringBeforeLast('/')
        lock(dir)
        assertFalse(IosFiles.isGone(pathOf(hidden)))
        val entries = ArchiveExport.archiveEntries(store, listOf(kept, hidden))
        val failure = assertFailsWith<IllegalStateException> { ArchiveExport.writeZip(MemorySink(), entries) }
        assertFalse(isMissingFile(failure))
        assertTrue(IosFiles.isGone("$dir-none/x.parquet"))
    }
}
