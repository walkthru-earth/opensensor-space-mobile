package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.JvmStorageTree
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiskBlockStoreTest {
    @Test
    fun `blocks round trip through files and a missing one reads null`() {
        val base = Files.createTempDirectory("blocks").toFile()
        try {
            val store = DiskBlockStore("${base.absolutePath}/feed-blocks", JvmAtomicFiles(base.resolve("tmp")), JvmStorageTree())
            assertNull(store.read("a"))
            assertEquals(emptyList<StoredBlock>(), store.list())
            store.write("a", byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), store.read("a"))
            assertEquals(listOf("a" to 3L), store.list().map { it.name to it.bytes })
            store.delete("a")
            store.delete("a")
            assertNull(store.read("a"))
        } finally {
            base.deleteRecursively()
        }
    }
}
