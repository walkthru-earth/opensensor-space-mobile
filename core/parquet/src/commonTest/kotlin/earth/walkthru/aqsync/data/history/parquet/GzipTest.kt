package earth.walkthru.aqsync.data.history.parquet

import kotlin.test.Test

/** The `expect` gzip: `java.util.zip` on the JVM, the system zlib on iOS, same answers. */
class GzipTest {

    @Test
    fun `gzip inflates one or two members and refuses bad sizes`() {
        // gzip of "hello hello hello\n" (python -c 'import gzip;print(list(gzip.compress(b"hello hello hello\n",mtime=0)))').
        val member = byteArrayOf(
            31, -117, 8, 0, 0, 0, 0, 0, 2, -1, -53, 72, -51, -55, -55, 87, -56, 64, -112, 92, 0,
            59, 124, -118, -33, 18, 0, 0, 0,
        )
        assertEquals("hello hello hello\n", Gzip.decompress(member, 18).decodeToString())
        assertEquals("two members", "hello hello hello\nhello hello hello\n", Gzip.decompress(member + member, 36).decodeToString())
        for (size in listOf(17, 19)) {
            try { Gzip.decompress(member, size); fail("size $size accepted") } catch (_: ParquetFormatException) { }
        }
        try { Gzip.decompress(member.copyOf(15), 18); fail("truncated accepted") } catch (_: ParquetFormatException) { }
    }
}
