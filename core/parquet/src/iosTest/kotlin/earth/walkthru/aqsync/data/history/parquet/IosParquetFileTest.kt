package earth.walkthru.aqsync.data.history.parquet

import kotlin.test.Test

/** The iOS edges: `NSData` mapping behind `ParquetFile.open(LocalFile)` (GZIP through the system zlib is covered by the common `GzipTest`). */
class IosParquetFileTest {

    @Test
    fun `mapped NSData open reads the same values as wrap`() {
        val path = "parquet/device/v1-lz4-90rows-dated.parquet"
        val mapped = ParquetFile.open(LocalFile("$fixtureRoot/$path"))
        assertEquals("v1-lz4-90rows-dated.parquet", mapped.source)
        val wrapped = ParquetFile.wrap(fixtureBytes(path))
        val a = mapped.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        val b = wrapped.readColumn("pm25_atmospheric_ug_m3") as ColumnValues.Int32
        kotlin.test.assertContentEquals(b.values, a.values)
        assertEquals(90, a.size)
    }

    @Test
    fun `a missing file is refused with a typed exception`() {
        try {
            ParquetFile.open(LocalFile("$fixtureRoot/no-such-file.parquet"))
            fail("missing file accepted")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message, e.message!!.contains("cannot open"))
        }
    }
}
