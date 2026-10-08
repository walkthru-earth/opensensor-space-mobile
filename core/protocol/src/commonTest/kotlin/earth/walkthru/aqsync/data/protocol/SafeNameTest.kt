package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeNameTest {
    private fun nameOfLength(length: Int): String {
        val prefix = "station=a1/year=2026/month=10/day=08/"
        val suffix = ".parquet"
        return prefix + "d".repeat(length - prefix.length - suffix.length) + suffix
    }

    @Test
    fun realArchiveNamesAreAccepted() {
        assertTrue(SafeName.isValid("station=cores3-a1b2/year=2026/month=10/day=08/data_1200_17_100-189-90.parquet"))
        assertTrue(SafeName.isValid("station=cores3-a1b2/unsynced/boot=17/data_0000_17_1-64-64.parquet"))
        assertTrue(SafeName.isValid("benchmarks/boot=3/lz4.parquet"))
        assertTrue(SafeName.isValid("legacy-parquet/data_0001.parquet"))
        assertTrue(SafeName.isValid("x.parquet"))
    }

    @Test
    fun lengthLimitIs399Bytes() {
        assertEquals(399, SafeName.MAX_BYTES)
        val at = nameOfLength(399)
        val over = nameOfLength(400)
        assertEquals(399, at.encodeToByteArray().size)
        assertEquals(400, over.encodeToByteArray().size)
        assertTrue(SafeName.isValid(at))
        assertFalse(SafeName.isValid(over))
        assertFalse(SafeName.isValid(nameOfLength(512)))
    }

    @Test
    fun pathTraversalAndAbsoluteNamesAreRejected() {
        assertFalse(SafeName.isValid("../etc/passwd.parquet"))
        assertFalse(SafeName.isValid("station=a/../b.parquet"))
        assertFalse(SafeName.isValid("a..parquet"))
        assertFalse(SafeName.isValid("/absolute.parquet"))
        assertFalse(SafeName.isValid("a//b.parquet"))
    }

    @Test
    fun charsetAndSuffixAreEnforced() {
        assertFalse(SafeName.isValid(""))
        assertFalse(SafeName.isValid(".parquet"))
        assertFalse(SafeName.isValid("Upper.parquet"))
        assertFalse(SafeName.isValid("space name.parquet"))
        assertFalse(SafeName.isValid("back\\slash.parquet"))
        assertFalse(SafeName.isValid("café.parquet"))
        assertFalse(SafeName.isValid("data.parquet.partial"))
        assertFalse(SafeName.isValid("notes.txt"))
        assertFalse(SafeName.isValid("data.PARQUET"))
    }
}
