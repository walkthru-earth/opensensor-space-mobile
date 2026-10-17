package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.RangePolicy
import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.net.BodyLimit
import earth.walkthru.aqsync.net.HttpRangeEngine
import earth.walkthru.aqsync.net.HttpRangeException
import earth.walkthru.aqsync.net.HttpRangeResponse
import earth.walkthru.aqsync.net.MemoryBlockStore
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.ACCRA
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.CAIRO
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.LAGOS
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.NAIROBI
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.endOf
import earth.walkthru.aqsync.publicfeed.OpenAqFixture.oct
import earth.walkthru.aqsync.storage.MemoryPressure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** [OpenAqAggregatesFeed] over a fake [HttpRangeEngine] serving the fixture and its manifest. No network. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAqAggregatesFeedTest {
    private val root = "https://example.test/openaq-aggregates"

    /** A static object store: `Range` (`bytes=-n`, `bytes=a-b`) and `If-Range` as S3 answers them. */
    private class FakeStore : HttpRangeEngine {
        val objects = mutableMapOf<String, Pair<ByteArray, String>>()
        val requests = mutableListOf<Pair<String, String?>>()

        /** Holds every cells file request until completed, so concurrent callers overlap. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
            requests += url to range
            if (url.endsWith(".parquet")) gate?.await()
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
        assertTrue(feed.isDaily)
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

    private fun cellsReads() = store.requests.count { it.first.endsWith(".parquet") }

    @Test
    fun `a file inside the opening tail is read with one request for every window`() = runTest {
        val partition = feed.latest()!!
        for (window in feed.windows) feed.cells(partition, window, 5, ranges = null)
        assertEquals(listOf<Pair<String, String?>>("$root/cells/h3_res=5/data.parquet" to "bytes=-${RangePolicy.CELLULAR.tailPrefetchBytes}"), store.requests.filter { it.first.endsWith(".parquet") })
    }

    @Test
    fun `24 hours and 7 days and later pans at one resolution read the network once`() = runTest {
        val partition = feed.latest()!!
        val day = feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        val week = feed.cells(partition, FeedWindow.WEEK, 5, ranges = null)
        val pan = feed.cells(partition, FeedWindow.DAY, 5, ranges = listOf(ACCRA..NAIROBI))
        val single = feed.cells(partition, FeedWindow.WEEK, 5, ranges = listOf(CAIRO..CAIRO))
        assertEquals(1, cellsReads())
        assertEquals(listOf(CAIRO, LAGOS, NAIROBI), day.rows.map { it.h3 })
        assertEquals(listOf(CAIRO, LAGOS, NAIROBI), week.rows.map { it.h3 })
        // Ranges filter the cached rows exactly as they pruned the file.
        assertEquals(listOf(NAIROBI), pan.rows.map { it.h3 })
        assertEquals(week.rows.first(), single.rows.single())
        assertEquals(5, pan.info.h3Res)
        assertEquals("24h", pan.info.window)
    }

    @Test
    fun `concurrent callers for one resolution share one download`() = runTest {
        val partition = feed.latest()!!
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val reads = listOf(
            async { feed.cells(partition, FeedWindow.DAY, 5, ranges = null) },
            async { feed.cells(partition, FeedWindow.WEEK, 5, ranges = null) },
            async { feed.cells(partition, FeedWindow.DAY, 5, ranges = listOf(NAIROBI..NAIROBI)) },
        )
        runCurrent()
        gate.complete(Unit)
        val (day, week, one) = reads.awaitAll()
        assertEquals(1, cellsReads())
        assertEquals(3, day.rows.size)
        assertEquals(3, week.rows.size)
        assertEquals(listOf(NAIROBI), one.rows.map { it.h3 })
    }

    @Test
    fun `a failed read is shared by its waiters and not cached`() = runTest {
        val partition = feed.latest()!!
        val cells = store.objects.remove("$root/cells/h3_res=5/data.parquet")!!
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val reads = listOf(
            async { runCatching { feed.cells(partition, FeedWindow.DAY, 5, ranges = null) } },
            async { runCatching { feed.cells(partition, FeedWindow.WEEK, 5, ranges = null) } },
        )
        runCurrent()
        gate.complete(Unit)
        val results = reads.awaitAll()
        assertTrue(results.all { it.exceptionOrNull() is HttpRangeException }, "$results")
        assertEquals(1, cellsReads())
        store.objects["$root/cells/h3_res=5/data.parquet"] = cells
        assertEquals(3, feed.cells(partition, FeedWindow.DAY, 5, ranges = null).rows.size)
        assertEquals(2, cellsReads())
    }

    @Test
    fun `a cancelled download leaves its waiter to read on its own`() = runTest {
        val partition = feed.latest()!!
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val first = async { feed.cells(partition, FeedWindow.DAY, 5, ranges = null) }
        runCurrent()
        val second = async { feed.cells(partition, FeedWindow.WEEK, 5, ranges = null) }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(3, second.await().rows.size)
        assertEquals(2, cellsReads())
    }

    @Test
    fun `a new partition from latest clears the rows`() = runTest {
        val partition = feed.latest()!!
        feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        // Same partition after the manifest is read again, the rows stay.
        clock.ms += OpenAqAggregatesFeed.MANIFEST_TTL_MS
        assertEquals(partition, feed.latest())
        feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        assertEquals(1, cellsReads())
        assertTrue(feed.cachedResolutions() > 0)
        store.objects["$root/manifest.json"] = """{"as_of":"2026-10-09"}""".encodeToByteArray() to "\"m2\""
        clock.ms += OpenAqAggregatesFeed.MANIFEST_TTL_MS
        val next = feed.latest()!!
        assertEquals(0, feed.cachedResolutions())
        feed.cells(next, FeedWindow.DAY, 5, ranges = null)
        assertEquals(2, cellsReads())
    }

    @Test
    fun `a download that started before a new partition is served but not kept`() = runTest {
        val partition = feed.latest()!!
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val read = async { feed.cells(partition, FeedWindow.DAY, 5, ranges = null) }
        runCurrent()
        // The manifest moves on while the old file is still downloading.
        store.objects["$root/manifest.json"] = """{"as_of":"2026-10-09"}""".encodeToByteArray() to "\"m2\""
        clock.ms += OpenAqAggregatesFeed.MANIFEST_TTL_MS
        assertEquals(FeedPartition("2026-10-09", 0), feed.latest())
        gate.complete(Unit)
        assertEquals(3, read.await().rows.size)
        assertEquals(0, feed.cachedResolutions(), "rows of the old generation are never kept")
        // So the old partition is read again rather than served from memory.
        feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        assertEquals(2, cellsReads())
    }

    @Test
    fun `memory pressure trims the rows and only a few resolutions are kept`() = runTest {
        val partition = feed.latest()!!
        feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        feed.trim(MemoryPressure.Level.Critical)
        assertEquals(0, feed.cachedResolutions())
        feed.cells(partition, FeedWindow.DAY, 5, ranges = null)
        assertEquals(2, cellsReads())
        // The fixture holds resolution 5 only. Other resolutions are served the same file.
        for (res in 2..7) store.objects["$root/cells/h3_res=$res/data.parquet"] = store.objects["$root/cells/h3_res=5/data.parquet"]!!
        for (res in 2..7) feed.cells(partition, FeedWindow.DAY, res, ranges = null)
        assertEquals(OpenAqAggregatesFeed.MAX_RESOLUTIONS, feed.cachedResolutions())
    }

    @Test
    fun `a second open of the view reads the cells from the block cache with no range request`() = runTest {
        val blocks = BlockCache(MemoryBlockStore(), quotaBytes = { 1L shl 30 })
        val first = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        val expected = first.cells(first.latest()!!, FeedWindow.DAY, 5, ranges = null)
        assertTrue(store.requests.any { it.first.endsWith(".parquet") })

        // A new feed, as after a restart, keeps nothing in memory but shares the disk cache.
        store.requests.clear()
        val second = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        assertEquals(expected.rows, second.cells(second.latest()!!, FeedWindow.DAY, 5, ranges = null).rows)
        assertEquals(listOf("$root/manifest.json"), store.requests.map { it.first })
    }

    @Test
    fun `a new as_of reads the cells again`() = runTest {
        val blocks = BlockCache(MemoryBlockStore(), quotaBytes = { 1L shl 30 })
        val first = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        first.cells(first.latest()!!, FeedWindow.DAY, 5, ranges = null)
        store.requests.clear()
        val second = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        second.cells(FeedPartition("2026-10-09", 0), FeedWindow.DAY, 5, ranges = null)
        assertTrue(store.requests.any { it.first.endsWith(".parquet") })
    }

    @Test
    fun `a file replaced under the same as_of with a new generation reads the new bytes`() = runTest {
        val blocks = BlockCache(MemoryBlockStore(), quotaBytes = { 1L shl 30 })
        val first = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        val partition = first.latest()!!
        val cairo = { table: CellsTable -> table.rows.single { it.h3 == CAIRO }.mean("pm25") }
        assertEquals(20.0, cairo(first.cells(partition, FeedWindow.DAY, 5, ranges = null)))

        // The writer replaces the file in place and bumps the generation, as_of stays.
        store.objects["$root/cells/h3_res=5/data.parquet"] = fixtureBytes(REPUBLISHED) to "\"c2\""
        store.objects["$root/manifest.json"] = """{"as_of":"2026-10-08","generation":"fixture-0002"}""".encodeToByteArray() to "\"m2\""
        clock.ms += OpenAqAggregatesFeed.MANIFEST_TTL_MS
        assertEquals(partition, first.latest())
        assertEquals(0, first.cachedResolutions())
        assertEquals(25.0, cairo(first.cells(partition, FeedWindow.DAY, 5, ranges = null)))

        // A restart reads the same generation from disk.
        store.requests.clear()
        val second = OpenAqAggregatesFeed(store, root = root, clock = clock, blocks = blocks)
        assertEquals(25.0, cairo(second.cells(second.latest()!!, FeedWindow.DAY, 5, ranges = null)))
        assertEquals(listOf("$root/manifest.json"), store.requests.map { it.first })
    }

    private companion object {
        const val REPUBLISHED = "openaq-aggregates-republished/cells/h3_res=5/data.parquet"
    }
}
