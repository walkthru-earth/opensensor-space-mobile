package space.opensensor.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrunedFilesCodecTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val name = "station=$thing/year=2024/month=10/day=1/file-1.parquet"

    @Test
    fun everyRecordReadsInFileOrder() {
        val a = PrunedFile(thing, name, 100, 4_294_967_295, 1_700_000_000_000)
        val b = PrunedFile("another-thing", name, 0, 0, 1)
        val text = "${PrunedFilesCodec.VERSION}\n$thing\t$name\t100\t4294967295\t1700000000000\nanother-thing\t$name\t0\t0\t1\n"
        assertEquals(listOf(a, b), PrunedFilesCodec.decode(text.encodeToByteArray()))
        assertEquals(emptyList(), PrunedFilesCodec.decode("${PrunedFilesCodec.VERSION}\n".encodeToByteArray()))
    }

    @Test
    fun anyMalformedOrUnsafeLineOrAnUnknownVersionMakesTheWholeFileUnreadable() {
        val good = "$thing\t$name\t100\t7\t1"
        assertEquals(listOf(PrunedFile(thing, name, 100, 7, 1)), PrunedFilesCodec.decode("${PrunedFilesCodec.VERSION}\n$good\n".encodeToByteArray()))
        val bad = listOf(
            "$thing\t$name\t100\t7",
            "$thing\t$name\tbig\t7\t1",
            "$thing\t$name\t-1\t7\t1",
            "../x\t$name\t100\t7\t1",
            "$thing\t../escape.parquet\t100\t7\t1",
        )
        for (line in bad) {
            assertNull(PrunedFilesCodec.decode("${PrunedFilesCodec.VERSION}\n$good\n$line\n".encodeToByteArray()), line)
        }
        assertNull(PrunedFilesCodec.decode("pruned-v9\n$good\n".encodeToByteArray()))
        assertNull(PrunedFilesCodec.decode(ByteArray(0)))
    }
}
