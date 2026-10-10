package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.MemoryRangeSource
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Bytes allocated on this thread to decode every bundled sample file, against the decoder this one
 * replaced. The baselines were measured with the same probe and the same fixtures on main at bed7949
 * (the `SuspendByteReader` feed reader and the per-page `ColumnChunkDecoder`), JDK 21 from Android
 * Studio. Each workload is warmed up, then the smallest of 15 runs counts. A cold reader is built per
 * file, so the footer cache does not hide the work.
 */
class AllocationTest {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().id)

    private fun measure(block: () -> Unit): Long {
        repeat(30) { block() }
        var best = Long.MAX_VALUE
        repeat(15) {
            val before = allocated()
            block()
            best = minOf(best, allocated() - before)
        }
        return best
    }

    private fun assertAtMost70Percent(label: String, baseline: Long, measured: Long) {
        println("ALLOC $label $measured bytes, baseline $baseline, ratio ${"%.2f".format(measured.toDouble() / baseline)}")
        assertTrue(measured <= baseline * 7 / 10, "$label allocated $measured bytes, more than 70% of the $baseline baseline")
    }

    @Test
    fun `decoding the sample feed allocates at most 70 percent of the old decoder`() {
        val partition = FeedPartition("2026-10-08", 10)
        val paths = FeedWindow.entries.flatMap { w -> (2..7).map { r -> "v1/${FeedPaths.cells(partition, w, r)}" } }
        val bytes = paths.associateWith { sampleBytes(it) }
        val berlin = ViewportCover.cover(platformH3Grid(), GeoBounds(13.2, 52.4, 13.6, 52.65), dataRes = 7).ranges

        val all = measure {
            runBlocking { for (p in paths) ParquetFeedReader().readCells(MemoryRangeSource(bytes.getValue(p), p), null) }
        }
        assertAtMost70Percent("feed, every cell", BASELINE_FEED_ALL, all)

        val city = measure {
            runBlocking { for (p in paths) ParquetFeedReader().readCells(MemoryRangeSource(bytes.getValue(p), p), berlin) }
        }
        assertAtMost70Percent("feed, Berlin viewport", BASELINE_FEED_BERLIN, city)

        val columns = measure {
            for (p in paths) {
                val f = ParquetFile.wrap(bytes.getValue(p))
                for (leaf in f.leaves) f.readColumn(leaf)
            }
        }
        assertAtMost70Percent("ParquetFile, every column", BASELINE_PARQUET_FILE, columns)
    }

    private companion object {
        const val BASELINE_FEED_ALL = 4_099_928L
        const val BASELINE_FEED_BERLIN = 2_413_408L
        const val BASELINE_PARQUET_FILE = 2_793_752L
    }
}
