package space.opensensor.archive

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileClockAnchorStoreTest : ClockAnchorStoreContract() {
    private val root = Files.createTempDirectory("clock-receipts-").toFile()
    override fun newStore(): ClockAnchorStore = FileClockAnchorStore(root)

    override fun writeMalformedReceipt() {
        File(root, "${clockTestAnchor.thing}/${clockTestAnchor.boot}/broken.anchor").writeText("interrupted")
        val moved = clockTestAnchor.copy(thing = "thing-b")
        File(root, "${clockTestAnchor.thing}/${clockTestAnchor.boot}/misplaced.anchor").writeBytes(ClockAnchorReceipt.encode(moved))
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun duplicateReceiptsAreImmutableAndSymlinkThingsAreRefused() = runTest {
        newStore().put(clockTestAnchor)
        val receipt = File(root, "${clockTestAnchor.thing}/${ClockAnchorReceipt.relativePath(clockTestAnchor)}")
        receipt.setLastModified(1_000L)
        newStore().put(clockTestAnchor)
        assertEquals(1_000L, receipt.lastModified())
        Files.createSymbolicLink(File(root, "linked-thing").toPath(), File(root, clockTestAnchor.thing).toPath())
        assertFailsWith<IllegalStateException> { newStore().anchors("linked-thing") }
        assertFailsWith<IllegalStateException> { newStore().put(clockTestAnchor.copy(thing = "linked-thing")) }
    }
}
