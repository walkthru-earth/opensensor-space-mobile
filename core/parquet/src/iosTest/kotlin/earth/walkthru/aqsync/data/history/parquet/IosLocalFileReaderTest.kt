@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.fstat
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What [LocalFileReader] holds, measured from the outside (#61): one descriptor per open file, closed
 * when [ParquetFile.close] returns. Kotlin/Native has no finalizer for a descriptor, so a reader that
 * relied on a collector would leave every one of these open.
 */
class IosLocalFileReaderTest {
    private val device = "parquet/device/v1-lz4-90rows-dated.parquet"

    /** Descriptors open in this process, probed with `fstat` over the low table. */
    private fun openDescriptors(): Int = memScoped {
        val st = alloc<stat>()
        (0 until 4096).count { fstat(it, st.ptr) == 0 }
    }

    @Test
    fun `each open file holds one descriptor and gives it back on close`() {
        val file = tempLocalFile("reader", fixtureBytes(device))
        val before = openDescriptors()

        val held = List(32) { ParquetFile.open(file) }
        held.forEach { assertEquals(90, it.readColumn("pm25_atmospheric_ug_m3").size) }
        assertEquals(before + 32, openDescriptors())

        held.forEach { it.close() }
        assertEquals(before, openDescriptors(), "every descriptor is closed on close")
        repeat(300) { ParquetFile.open(file).use { pf -> pf.readColumn("pm25_atmospheric_ug_m3") } }
        assertEquals(before, openDescriptors(), "open and close in a loop leaks nothing")
    }

    @Test
    fun `a directory is refused at open and holds no descriptor`() {
        val before = openDescriptors()
        assertFailsWith<ParquetFormatException> { LocalFileReader(platform.Foundation.NSTemporaryDirectory()) }
        assertEquals(before, openDescriptors())
    }

    @Test
    fun `a closed reader refuses reads and closes once`() {
        val reader = LocalFileReader(tempLocalFile("closed-reader", ByteArray(16) { it.toByte() }).path)
        assertEquals(16L, reader.size)
        reader.close()
        assertFailsWith<IllegalStateException> { reader.read(0, 4) }
        // A second close is harmless.
        reader.close()
    }
}
