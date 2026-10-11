package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.RangePolicy
import earth.walkthru.aqsync.net.BodyLimit
import earth.walkthru.aqsync.net.HttpRangeEngine
import earth.walkthru.aqsync.net.HttpRangeException
import earth.walkthru.aqsync.net.HttpRangeResponse
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.ACCRA
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.CAIRO
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.LAGOS
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.NAIROBI
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.endOf
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.oct
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/** [OpenAqAggregatesFeed] over a fake [HttpRangeEngine] serving the fixture and its manifest. No network. */
class OpenAqAggregatesFeedTest {
    private val root = "https://example.test/openaq-aggregates"

    /** A static object store: `Range` (`bytes=-n`, `bytes=a-b`) and `If-Range` as S3 answers them. */
    private class FakeStore : HttpRangeEngine {
        val objects = mutableMapOf<String, Pair<ByteArray, String>>()
        val requests = mutableListOf<Pair<String, String?>>()

        override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
            requests += url to range
            val (bytes, etag) = objects[url] ?: return HttpRangeResponse(404, null, null, null, ByteArray(0))
            val size = bytes.size.toLong()
            if (range == null || (ifRange != null && ifRange != etag)) return HttpRangeResponse(200, etag, null, null, bytes.copyOf())
            val spec = range.removePrefix("bytes=")
            val (first, last) = if (spec.startsWith("-")) {
                maxOf(0L, size - spec.drop(1).toLong()) to size - 1
            } else {
                spec.substringBefore('-').toLong() to minOf(spec.substringAfter('-').toLong(), size - 1)
            }
            return HttpRangeResponse(206, etag, "bytes $first-$last/$size", null, bytes.copyOfRange(first.toInt(), last.toInt() + 1))
        }
    }

    private class FakeClock(var ms: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }

    private val store = FakeStore().apply {
        objects["$root/manifest.json"] = fixtureBytes(OpenAqFixture.MANIFEST) to "\"m1\""
        objects["$root/cells/h3_res=5/data.parquet"] = fixtureBytes(OpenAqFixture.CELLS) to "\"c1\""
    }
    private val clock = FakeClock(1_000_000L)
    private val feed = OpenAqAggregatesFeed(store, root = "$root/", clock = clock)

    private fun manifestReads() = store.requests.count { it.first.endsWith("manifest.json") }

    @Test
    fun `the feed is live data for 24 hours and 7 days`() {
        assertFalse(feed.isSample)
        assertEquals(listOf(FeedWindow.DAY, FeedWindow.WEEK), feed.windows)
        assertEquals("$root/cells/h3_res=7/data.parquet", feed.cellsUrl(7))
    }

    @Test
    fun `latest is the manifest as_of cached for five minutes`() = runTest {
        assertEquals(FeedPartition("2026-10-08", 0), feed.latest())
        assertEquals(1, manifestReads())
        clock.ms += OpenAqAggregatesFeed.MANIFEST_TTL_MS - 1
        assertEquals(FeedPartition("2026-10-08", 0), feed.latest())
        assertEquals(1, manifestReads())
        // A new generation is seen once the five minutes are over.
        store.objects["$root/manifest.json"] = """{"as_of":"2026-10-09","generation":"g2","unknown":[1]}""".encodeToByteArray() to "\"m2\""
        clock.ms += 1
        assertEquals(FeedPartition("2026-10-09", 0), feed.latest())
        assertEquals(2, manifestReads())
    }

    @Test
    fun `a manifest without as_of has no partition and a failed read throws and is not cached`() = runTest {
        store.objects.remove("$root/manifest.json")
        assertEquals(404, assertFailsWith<HttpRangeException> { feed.latest() }.status)
        store.objects["$root/manifest.json"] = """{"generation":"g"}""".encodeToByteArray() to "\"m3\""
        assertNull(feed.latest())
        assertEquals(2, manifestReads())
    }

    @Test
    fun `the last hour is empty without a request`() = runTest {
        val table = feed.cells(FeedPartition("2026-10-08", 0), FeedWindow.LAST_HOUR, 5, ranges = null)
        assertSame(CellsTable.EMPTY, table)
        assertEquals(emptyList<Pair<String, String?>>(), store.requests)
    }

    @Test
    fun `24 hours is the newest day per property in the seven days`() = runTest {
        val table = feed.cells(feed.latest()!!, FeedWindow.DAY, 5, ranges = null)
        assertFalse(table.info.sample)
        assertEquals("24h", table.info.window)
        assertEquals(5, table.info.h3Res)
        // Accra measured only before the seven days ending at as_of, so it is dropped.
        assertEquals(listOf(CAIRO, LAGOS, NAIROBI), table.rows.map { it.h3 })
        val (cairo, lagos, nairobi) = table.rows
        assertEquals(CellRow(CAIRO, 2, endOf(oct(8)), mapOf("pm25" to CellStat(2, 20.0, 12.0, 31.0), "temperature" to CellStat(2, 26.0, 21.0, 30.0))), cairo)
        assertEquals(CellRow(LAGOS, 1, endOf(oct(7)), mapOf("humidity" to CellStat(1, 70.0, 64.0, 77.0))), lagos)
        // PM10 from the 5th and PM2.5 from the 7th. The 8th has stations but no value, so it is not used.
        assertEquals(CellRow(NAIROBI, 2, endOf(oct(7)), mapOf("pm10" to CellStat(1, 33.0, 25.0, 41.0), "pm25" to CellStat(2, 12.0, 8.0, 17.0))), nairobi)
    }

    @Test
    fun `7 days folds the daily rows skipping gap days`() = runTest {
        val table = feed.cells(feed.latest()!!, FeedWindow.WEEK, 5, ranges = null)
        assertEquals("7d", table.info.window)
        assertEquals(listOf(CAIRO, LAGOS, NAIROBI), table.rows.map { it.h3 })
        val (cairo, lagos, nairobi) = table.rows
        // PM2.5 on the 2nd, 5th, 6th and 8th (30, 50, 40, 20). September 30 is outside the window.
        assertEquals(CellRow(CAIRO, 3, endOf(oct(8)), mapOf("pm25" to CellStat(3, 35.0, 12.0, 70.0), "temperature" to CellStat(3, 27.0, 21.0, 33.0))), cairo)
        assertEquals(CellRow(LAGOS, 1, endOf(oct(7)), mapOf("humidity" to CellStat(1, 75.0, 64.0, 91.0))), lagos)
        assertEquals(CellRow(NAIROBI, 2, endOf(oct(7)), mapOf("pm10" to CellStat(1, 33.0, 25.0, 41.0), "pm25" to CellStat(2, 12.0, 8.0, 17.0))), nairobi)
    }

    @Test
    fun `h3 ranges limit the cells and a missing file throws`() = runTest {
        val partition = feed.latest()!!
        val view = feed.cells(partition, FeedWindow.DAY, 5, ranges = listOf(ACCRA..NAIROBI))
        assertEquals(listOf(NAIROBI), view.rows.map { it.h3 })
        assertFailsWith<HttpRangeException> { feed.cells(partition, FeedWindow.DAY, 6, ranges = null) }
    }

    @Test
    fun `a file inside the opening tail is read with one request per window`() = runTest {
        val partition = feed.latest()!!
        for (window in feed.windows) {
            store.requests.clear()
            feed.cells(partition, window, 5, ranges = null)
            assertEquals(listOf<Pair<String, String?>>("$root/cells/h3_res=5/data.parquet" to "bytes=-${RangePolicy.CELLULAR.tailPrefetchBytes}"), store.requests)
        }
    }
}
