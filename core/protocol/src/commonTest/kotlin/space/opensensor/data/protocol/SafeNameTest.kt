package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeNameTest {
    /** A name of [length] bytes whose segments all stay within [SafeName.MAX_SEGMENT_BYTES]. */
    private fun nameOfLength(length: Int): String {
        val prefix = "thing=a1/year=2026/month=10/day=08/"
        val suffix = ".parquet"
        var left = length - prefix.length - suffix.length
        val body = StringBuilder()
        while (left > 201) {
            body.append("d".repeat(200)).append('/')
            left -= 201
        }
        return prefix + body + "d".repeat(left) + suffix
    }

    /** A name whose last segment is [segment] bytes, `.parquet` included. */
    private fun nameWithSegment(segment: Int): String = "thing=a1/" + "d".repeat(segment - ".parquet".length) + ".parquet"

    @Test
    fun realArchiveNamesAreAccepted() {
        assertTrue(SafeName.isValid("thing=cores3-a1b2/year=2026/month=10/day=08/data_1200_17_100-189-90.parquet"))
        assertTrue(SafeName.isValid("thing=cores3-a1b2/unsynced/boot=17/data_0000_17_1-64-64.parquet"))
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
    fun segmentLimitIs241Bytes() {
        assertEquals(241, SafeName.MAX_SEGMENT_BYTES)
        assertEquals(255, SafeName.MAX_SEGMENT_BYTES + ".verified.json".length)
        assertTrue(SafeName.isValid(nameWithSegment(241)))
        assertFalse(SafeName.isValid(nameWithSegment(242)))
        // A directory segment is bounded too, not only the file name.
        assertTrue(SafeName.isValid("d".repeat(241) + "/x.parquet"))
        assertFalse(SafeName.isValid("d".repeat(242) + "/x.parquet"))
    }

    @Test
    fun pathTraversalAndAbsoluteNamesAreRejected() {
        assertFalse(SafeName.isValid("../etc/passwd.parquet"))
        assertFalse(SafeName.isValid("thing=a/../b.parquet"))
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
