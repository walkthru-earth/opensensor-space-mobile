package space.opensensor.data.history.parquet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The app's registry decodes a PyArrow ZSTD GeoParquet fixture that the pure built-ins refuse by name. */
class AppParquetCodecsTest {

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("geoparquet/$name")) { "missing $name" }.readBytes()

    @Test
    fun `ZSTD pages decode through zstd-jni and are refused without it`() {
        val bytes = fixture("pyarrow/pa-zstd-dict-v2.parquet")
        val pf = ParquetFile.wrap(bytes, "zstd", AppParquetCodecs.all)
        assertEquals(14L, pf.numRows)
        val geometry = pf.readGeometry(pf.geometryColumns.single())
        assertEquals("POINT (10 20)", geometry[0]?.toWkt())
        assertEquals("POINT ZM (7 8 9 10)", geometry[10]?.toWkt())
        assertEquals(2, geometry.count { it == null })
        try {
            ParquetFile.wrap(bytes, "zstd", ParquetCodecs.builtIn).readColumn("id")
            fail("expected ZSTD to be refused")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message, e.message!!.contains("ZSTD"))
        }
    }
}
