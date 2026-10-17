@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosPrunedFilesTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/pruned-test-" + NSUUID().UUIDString()
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val a = PrunedFile(station, "station=$station/year=2024/month=10/day=1/a.parquet", 100, 7, 1)
    private val b = PrunedFile(station, "station=$station/year=2024/month=10/day=2/b.parquet", 200, 8, 2)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @Test
    fun recordsSurviveANewInstanceAndRemoveDropsOne() = runTest {
        IosPrunedFiles(root).apply {
            put(a)
            put(b)
            remove(b.key)
        }
        assertEquals(listOf(a), IosPrunedFiles(root).observeAll().first())
    }

    @Test
    fun clearForgetsEveryRecordAndCountsThem() = runTest {
        val files = IosPrunedFiles(root)
        files.put(a)
        files.put(b)
        assertEquals(2, files.clear())
        assertEquals(emptyList(), files.observeAll().first())
        assertEquals(emptyList(), IosPrunedFiles(root).observeAll().first())
    }

    @Test
    fun aCorruptFileRefusesChangesUntilClear() = runTest {
        IosPrunedFiles(root).put(a)
        val text = "${PrunedFilesCodec.VERSION}\n$station\tbroken\n"
        IosFiles.writeAtomically("$root/pruned.v1", text.encodeToByteArray())

        val files = IosPrunedFiles(root)
        assertEquals(emptyList(), files.observeAll().first())
        assertFailsWith<IllegalStateException> { files.put(b) }
        assertFailsWith<IllegalStateException> { files.remove(a.key) }
        assertEquals(text, IosFiles.readSmall("$root/pruned.v1", PrunedFilesCodec.MAX_BYTES)?.decodeToString())

        assertEquals(0, files.clear())
        files.put(b)
        assertEquals(listOf(b), IosPrunedFiles(root).observeAll().first())
    }
}
