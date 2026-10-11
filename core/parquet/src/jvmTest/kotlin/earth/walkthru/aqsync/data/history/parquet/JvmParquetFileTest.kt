package earth.walkthru.aqsync.data.history.parquet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** The JVM edges: `FileChannel` mapping behind `ParquetFile.open(File)` and the `OutputStream` writer. */
class JvmParquetFileTest {

    private val device = "parquet/device/v1-lz4-90rows-dated.parquet"

    @Test
    fun `memory-mapped open reads the same values as wrap`() {
        val bytes = fixtureBytes(device)
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        val mapped = ParquetFile.open(tmp)
        assertEquals(tmp.name, mapped.source)
        val wrapped = ParquetFile.wrap(bytes)
        val a = mapped.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        val b = wrapped.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        assertTrue(a.values.contentEquals(b.values))
        assertEquals(90, a.size)
    }

    @Test
    fun `mapped reader serves absolute ranges from many threads`() {
        val bytes = fixtureBytes(device)
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(bytes) }
        val reader = MappedFileReader(tmp)
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
    }

    @Test
    fun `a closed mapped reader refuses reads`() {
        val tmp = File.createTempFile("aq-parquet", ".parquet").apply { deleteOnExit(); writeBytes(fixtureBytes(device)) }
        val reader = MappedFileReader(tmp)
        assertFalse(reader.isClosed)
        ParquetFile.open(reader, tmp.name).use { assertEquals(90L, it.metadata.numRows) }

        assertTrue(reader.isClosed)
        assertThrows(IllegalStateException::class.java) { reader.read(0, 4) }
        // A second close is harmless.
        reader.close()
        assertTrue(reader.isClosed)
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
