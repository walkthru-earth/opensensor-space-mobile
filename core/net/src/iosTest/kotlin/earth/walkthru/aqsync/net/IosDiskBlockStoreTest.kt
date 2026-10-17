package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.IosStorageTree
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
class IosDiskBlockStoreTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/blocks-" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun blocksRoundTripThroughFilesAndAMissingOneReadsNull() {
        NSFileManager.defaultManager.createDirectoryAtPath("$base/tmp", true, null, null)
        val store = DiskBlockStore("$base/feed-blocks", IosAtomicFiles("$base/tmp"), IosStorageTree())
        assertNull(store.read("a"))
        assertEquals(emptyList<StoredBlock>(), store.list())
        store.write("a", byteArrayOf(1, 2, 3))
        store.write("empty", ByteArray(0))
        assertContentEquals(byteArrayOf(1, 2, 3), store.read("a"))
        assertContentEquals(ByteArray(0), store.read("empty"))
        assertEquals(setOf("a" to 3L, "empty" to 0L), store.list().map { it.name to it.bytes }.toSet())
        store.delete("a")
        store.delete("a")
        assertNull(store.read("a"))
    }
}
