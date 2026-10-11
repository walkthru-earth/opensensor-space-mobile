package earth.walkthru.aqsync.data.history.parquet

import com.sun.management.UnixOperatingSystemMXBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory

/** The JVM edges: the positional `FileChannel` reader behind `ParquetFile.open(File)` and the `OutputStream` writer. */
class JvmParquetFileTest {

    private val device = "parquet/device/v1-lz4-90rows-dated.parquet"

    @Test
    fun `file open reads the same values as wrap`() {
        val bytes = fixtureBytes(device)
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        val opened = ParquetFile.open(tmp)
        assertEquals(tmp.name, opened.source)
        val wrapped = ParquetFile.wrap(bytes)
        val a = opened.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        val b = wrapped.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        assertTrue(a.values.contentEquals(b.values))
        assertEquals(90, a.size)
        opened.close()
    }

    @Test
    fun `file reader serves absolute ranges from many threads`() {
        val bytes = fixtureBytes(device)
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        val reader = LocalFileReader(tmp)
        assertEquals(bytes.size.toLong(), reader.size)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(200) { i ->
                    val off = ((t * 977 + i * 131) % (bytes.size - 64)).toLong()
                    assertArrayEquals(bytes.copyOfRange(off.toInt(), off.toInt() + 64), reader.read(off, 64))
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        reader.close()
    }

    @Test
    fun `a closed file reader refuses reads`() {
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(fixtureBytes(device)) }
        val reader = LocalFileReader(tmp)
        ParquetFile.open(reader, tmp.name).use { assertEquals(90L, it.metadata.numRows) }

        assertThrows(IllegalStateException::class.java) { reader.read(0, 4) }
        // A second close is harmless.
        reader.close()
    }

    @Test
    fun `a directory is refused at open, as the mapping refused it`() {
        val dir = kotlin.io.path.createTempDirectory("aq-parquet").toFile().apply { deleteOnExit() }
        assertThrows(java.io.FileNotFoundException::class.java) { LocalFileReader(dir) }
    }

    /**
     * What the reader holds, measured from the outside (#61): opening and reading a file maps nothing,
     * and the descriptor is gone when [ParquetFile.close] returns, with no collector run in between.
     * A reader that leaked its descriptor, or kept a mapping until GC, would leave hundreds behind here.
     */
    @Test
    fun `opened files hold no mapping and give their descriptor back on close`() {
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(fixtureBytes(device)) }
        val os = ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean
        val mapped = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).single { it.name == "mapped" }
        val mappedBefore = mapped.count
        val fdsBefore = os.openFileDescriptorCount

        val held = List(64) { ParquetFile.open(tmp) }
        held.forEach { assertEquals(90, it.readColumn("pm25_atmospheric_ug_m3").size) }
        assertEquals("no file is mapped", mappedBefore, mapped.count)
        assertTrue("each open file holds a descriptor", os.openFileDescriptorCount >= fdsBefore + 64)

        held.forEach { it.close() }
        assertTrue("every descriptor is closed without a GC", os.openFileDescriptorCount < fdsBefore + 8)
        repeat(500) { ParquetFile.open(tmp).use { pf -> pf.readColumn("pm25_atmospheric_ug_m3") } }
        assertTrue("open and close in a loop leaks nothing", os.openFileDescriptorCount < fdsBefore + 8)
        assertEquals(mappedBefore, mapped.count)
    }

    @Test
    fun `the OutputStream writer keeps its old call shape`() {
        val out = ByteArrayOutputStream()
        val w = ParquetWriter(out, listOf(ParquetWriter.Column("v", ParquetType.INT64)), ParquetCodec.LZ4_RAW, mapOf("k" to "v"))
        w.begin()
        w.rowGroup(listOf(ColumnValues.Int64(longArrayOf(3, 1, 2), null)))
        w.finish()
        val pf = ParquetFile.wrap(out.toByteArray())
        assertEquals("v", pf.metadata.keyValueMetadata["k"])
        assertArrayEquals(longArrayOf(3, 1, 2), (pf.readColumn("v") as ColumnValues.Int64).values)
    }
}
