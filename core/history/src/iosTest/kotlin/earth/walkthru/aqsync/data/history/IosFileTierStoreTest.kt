@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosFileTierStoreTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/tier-test-" + NSUUID().UUIDString()
    private val atomic = IosAtomicFiles("$root/temps")
    private val outside = "$root-outside"
    private val day = 20_000L
    private val sample = mapOf(Resolution.HOUR to mapOf("pm25" to listOf(TierBucket(day * 86_400, 10, 8, 1, 12f, 2f, 25f))))

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
        NSFileManager.defaultManager.removeItemAtPath(outside, null)
    }

    @Test
    fun savedTiersReadAfterRestartAndRetainIsStationScoped() = runTest {
        val store = IosFileTierStore(root, atomic)
        store.save("s1", day, "sig", sample)
        store.save("s1", day + 1, "next", sample)
        store.save("s2", day, "other", sample)
        val restarted = IosFileTierStore(root, atomic)
        assertEquals(sample, restarted.load("s1", day, "sig"))
        assertNull(restarted.load("s1", day, "changed"))
        restarted.retain("s1", setOf(day + 1))
        assertNull(restarted.load("s1", day, "sig"))
        assertEquals(sample, restarted.load("s1", day + 1, "next"))
        assertEquals(sample, restarted.load("s2", day, "other"))
    }

    @Test
    fun savedDaysListOneStationAndLoadSavedKeepsTheirSignature() = runTest {
        val store = IosFileTierStore(root, atomic)
        store.save("s1", day, "sig", sample)
        store.save("s1", day + 3, "later", sample)
        store.save("s2", day + 1, "other", sample)
        val restarted = IosFileTierStore(root, atomic)
        assertEquals(setOf(day, day + 3), restarted.savedDays("s1"))
        val saved = restarted.loadSaved("s1", day + 3)!!
        assertEquals("later", saved.signature)
        assertEquals(sample, saved.tiers)
        assertNull(restarted.loadSaved("s1", day + 1))
        assertEquals(emptySet(), restarted.savedDays("none"))
    }

    @Test
    fun aTruncatedOrPurgedTierFileIsACacheMiss() = runTest {
        val store = IosFileTierStore(root, atomic)
        store.save("s1", day, "sig", sample)
        val path = "$root/${ArchiveLayout.derivedDayPath("s1", day).removeSuffix(".parquet")}.tiers"
        atomic.writeAtomically(path, byteArrayOf(1, 0), earth.walkthru.aqsync.storage.StorageClass.Rebuildable)
        assertNull(store.load("s1", day, "sig"))
        store.save("s1", day, "sig", sample)
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        assertNull(store.load("s1", day, "sig"))
    }

    @Test
    fun symlinkedParentsRefuseReadWriteAndRetainWithoutChangingOutsideFiles() = runTest {
        val fm = NSFileManager.defaultManager
        val relative = ArchiveLayout.derivedDayPath("s1", day).removeSuffix(".parquet") + ".tiers"
        val sentinel = TierCodec.encode("sig", sample)
        // Cover a station parent and its higher derived parent, both before the root boundary.
        for (parent in listOf("derived/v1/s1", "derived")) {
            fm.removeItemAtPath(root, null)
            fm.removeItemAtPath(outside, null)
            val outsideFile = "$outside/${relative.removePrefix("$parent/")}"
            val iterationAtomic = IosAtomicFiles("$root/temps")
            iterationAtomic.writeAtomically(outsideFile, sentinel, StorageClass.Rebuildable)
            val linkedParent = "$root/$parent"
            assertTrue(fm.createDirectoryAtPath(linkedParent.substringBeforeLast('/'), true, null, null))
            assertTrue(fm.createSymbolicLinkAtPath(linkedParent, outside, null))
            val store = IosFileTierStore(root, iterationAtomic)
            assertFailsWith<IllegalStateException> { store.load("s1", day, "sig") }
            assertFailsWith<IllegalStateException> { store.save("s1", day, "replacement", emptyMap()) }
            assertFailsWith<IllegalStateException> { store.retain("s1", emptySet()) }
            val after = LocalFileReader(outsideFile).use { reader -> reader.read(0, reader.size.toInt()) }
            assertContentEquals(sentinel, after, "the outside sentinel survives all operations through $parent")
        }
    }
}
