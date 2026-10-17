package earth.walkthru.aqsync.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrunedFilesCodecTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val name = "station=$station/year=2024/month=10/day=1/file-1.parquet"

    @Test
    fun recordsRoundTripSortedByStationAndName() {
        val a = PrunedFile(station, name, 100, 4_294_967_295, 1_700_000_000_000)
        val b = PrunedFile("another-station", name, 0, 0, 1)
        assertEquals(listOf(a, b), PrunedFilesCodec.decode(PrunedFilesCodec.encode(listOf(b, a))))
        assertEquals(emptyList(), PrunedFilesCodec.decode(PrunedFilesCodec.encode(emptyList())))
    }

    @Test
    fun malformedOrUnsafeLinesAreSkippedAndAnUnknownVersionReadsEmpty() {
        val good = "$station\t$name\t100\t7\t1"
        val text = listOf(
            PrunedFilesCodec.VERSION,
            good,
            "$station\t$name\t100\t7",
            "$station\t$name\tbig\t7\t1",
            "$station\t$name\t-1\t7\t1",
            "../x\t$name\t100\t7\t1",
            "$station\t../escape.parquet\t100\t7\t1",
            "",
        ).joinToString("\n")
        assertEquals(listOf(PrunedFile(station, name, 100, 7, 1)), PrunedFilesCodec.decode(text.encodeToByteArray()))
        assertTrue(PrunedFilesCodec.decode("pruned-v9\n$good\n".encodeToByteArray()).isEmpty())
        assertTrue(PrunedFilesCodec.decode(ByteArray(0)).isEmpty())
    }
}
