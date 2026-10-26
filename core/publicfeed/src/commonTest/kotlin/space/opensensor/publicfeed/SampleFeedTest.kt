package space.opensensor.publicfeed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import space.opensensor.data.history.parquet.ByteView
import space.opensensor.data.history.parquet.MemoryRangeSource
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.RangeSource
import space.opensensor.data.history.parquet.SourceId
import space.opensensor.storage.MemoryPressure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bytes of a bundled sample file, by its path under `discover-sample/`. */
expect fun sampleBytes(path: String): ByteArray

/**
 * Reads the sample feed the app bundles (tools/discover-fixture) through the common Parquet reader
 * and the platform H3 binding (`com.uber:h3` on the JVM, the H3 C library on iOS), the same path
 * each app takes. Vectors come from Python `h3` 4.5.0.
 */
class SampleFeedTest {
    private val feed = BundledSampleFeed(root = "v1") { path -> sampleBytes(path) }
    private val h3 = platformH3Grid()

    @Test
    fun `latest lets cancellation through and maps other failures to null`() = runTest {
        val cancelled = BundledSampleFeed(root = "v1") { throw CancellationException("left Discover") }
        assertFailsWith<CancellationException> { cancelled.latest() }
        val broken = BundledSampleFeed(root = "v1") { "not json".encodeToByteArray() }
        assertNull(broken.latest())
    }

    @Test
    fun `h3 binding matches python vectors`() {
        assertEquals(608533319822344191L, h3.latLngToCell(52.52, 13.405, 7))
        val centre = h3.cellToLatLng(608533319822344191L)
        assertEquals(52.52574846527661, centre.lat, absoluteTolerance = 1e-9)
        assertEquals(13.409061814348233, centre.lng, absoluteTolerance = 1e-9)
        assertEquals(6, h3.cellToBoundary(608533319822344191L).size)
        val box = listOf(LatLng(52.4, 13.3), LatLng(52.4, 13.5), LatLng(52.6, 13.5), LatLng(52.6, 13.3))
        assertEquals(68, h3.polygonToCells(box, 7).size)
    }

    @Test
    fun `every file is sample data with a dictionary and the crowd rule`() = runTest {
        val partition = feed.latest()!!
        assertEquals(FeedPartition("2026-10-08", 10), partition)
        for (window in FeedWindow.entries) {
            for (res in 2..7) {
                val table = feed.cells(partition, window, res, ranges = null)
                assertTrue(table.info.sample)
                assertEquals(res, table.info.h3Res)
                assertEquals(window.key, table.info.window)
                assertEquals(listOf("pm25", "pm10", "temperature", "humidity", "co2"), table.info.properties.map { it.id })
                assertTrue(table.rows.isNotEmpty())
                assertEquals(table.rows.sortedBy { it.h3 }, table.rows)
                for (row in table.rows) {
                    assertEquals(res, H3Index.resolution(row.h3))
                    assertTrue(row.devices >= 3, "crowd rule")
                    for ((_, stat) in row.stats) {
                        val mean = stat.mean!!
                        assertTrue(stat.min!! <= mean && mean <= stat.max!!)
                    }
                }
            }
        }
    }

    @Test
    fun `a city viewport prunes pages and keeps only its cells`() = runTest {
        val partition = feed.latest()!!
        val berlin = GeoBounds(13.2, 52.4, 13.6, 52.65)
        val cover = ViewportCover.cover(h3, berlin, dataRes = 7)
        assertTrue(cover.coverCells in 1..ViewportCover.MAX_CELLS)
        val all = feed.cells(partition, FeedWindow.DAY, 7, ranges = null)
        val view = feed.cells(partition, FeedWindow.DAY, 7, cover.ranges)
        // One sorted row group per file: the page index, not the row group statistics, does the pruning.
        assertEquals(1, view.rowGroupsTotal)
        assertTrue(view.pagesRead < view.pagesTotal, "pruned to ${view.pagesRead} of ${view.pagesTotal} pages")
        assertEquals(all.pagesTotal, all.pagesRead)
        assertTrue(view.rows.isNotEmpty())
        assertTrue(view.rows.size < all.rows.size)
        val berlinCell = h3.latLngToCell(52.52, 13.405, 7)
        assertTrue(view.rows.any { it.h3 == berlinCell })
        // Every row in view is a Berlin cell, and every Berlin cell of the file is in view.
        val expected = all.rows.filter { H3Index.inRanges(it.h3, cover.ranges!!) }
        assertEquals(expected, view.rows)
        // Lisbon measures weather only, so its cells have no PM2.5 (null, never zero).
        val lisbon = feed.cells(partition, FeedWindow.DAY, 7, ViewportCover.cover(h3, GeoBounds(-9.3, 38.6, -9.0, 38.85), 7).ranges)
        assertTrue(lisbon.rows.isNotEmpty())
        assertTrue(lisbon.rows.all { it.mean("pm25") == null && it.mean("temperature") != null })
    }

    @Test
    fun `wide views coarsen the cover within budget`() {
        val europe = GeoBounds(-11.0, 35.0, 32.0, 66.0)
        val cover = ViewportCover.cover(h3, europe, dataRes = 7)
        assertTrue(cover.coverCells <= ViewportCover.MAX_CELLS)
        assertTrue(cover.coverRes < 7)
        val world = ViewportCover.cover(h3, GeoBounds(-180.0, -80.0, 180.0, 80.0), dataRes = 2)
        assertEquals(null, world.ranges)
    }

    @Test
    fun `geojson carries colour only for measured cells`() = runTest {
        val partition = feed.latest()!!
        val rows = feed.cells(partition, FeedWindow.DAY, 5, ranges = null).rows
        val pm25 = feed.cells(partition, FeedWindow.DAY, 5, ranges = null).info.properties.single { it.id == "pm25" }
        val scale = CellScale.of(pm25, rows.mapNotNull { it.mean("pm25") })
        // The sample has no Collection, so the scales the app ships colour it.
        assertIs<CellScale.Steps>(scale)
        val json = CellGeoJson.build(h3, rows, "pm25", scale) { false }
        assertTrue(json.startsWith("{\"type\":\"FeatureCollection\""))
        assertEquals(rows.size, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertEquals(rows.count { it.mean("pm25") != null }, Regex("\"color\":\"#").findAll(json).count())
    }

    @Test
    fun `the range reader returns the rows of the whole-file reader with at most one request per file`() = runTest {
        val partition = feed.latest()!!
        val berlin = ViewportCover.cover(h3, GeoBounds(13.2, 52.4, 13.6, 52.65), dataRes = 7).ranges
        for (window in FeedWindow.entries) {
            for (res in 2..7) {
                val path = "v1/${FeedPaths.cells(partition, window, res)}"
                val bytes = sampleBytes(path)
                for (ranges in listOf(null, berlin)) {
                    val counting = CountingSource(MemoryRangeSource(bytes, path))
                    val table = ParquetFeedReader().readCells(counting, ranges)
                    assertEquals(referenceRows(bytes, ranges), table.rows, "$path ranges=${ranges?.size}")
                    assertTrue(counting.requests <= 1, "$path made ${counting.requests} requests")
                }
            }
        }
    }

    @Test
    fun `memory pressure empties the byte cache and the next read loads the file again`() = runTest {
        var loads = 0
        val counted = BundledSampleFeed(root = "v1") { path -> loads++; sampleBytes(path) }
        val partition = counted.latest()!!
        loads = 0
        val before = FeedWindow.entries.flatMap { window -> (2..7).map { res -> counted.cells(partition, window, res, ranges = null) } }
        val files = FeedWindow.entries.size * 6
        assertEquals(files, loads)
        val full = counted.cachedBytes()
        assertTrue(full in 1..BundledSampleFeed.DEFAULT_BUDGET_BYTES, "cached $full")

        // A second pass is served from the cache.
        counted.cells(partition, FeedWindow.DAY, 5, ranges = null)
        assertEquals(files, loads)

        counted.trim(MemoryPressure.Level.Moderate)
        assertTrue(counted.cachedBytes() <= BundledSampleFeed.DEFAULT_BUDGET_BYTES / 2)

        counted.trim(MemoryPressure.Level.Critical)
        assertEquals(0L, counted.cachedBytes())

        val after = FeedWindow.entries.flatMap { window -> (2..7).map { res -> counted.cells(partition, window, res, ranges = null) } }
        assertEquals(before.map { it.rows }, after.map { it.rows })
        assertEquals(2 * files, loads)
        assertEquals(full, counted.cachedBytes())
    }

    /** Counts every request: a tail read and each range handed to the source. */
    private class CountingSource(private val inner: RangeSource) : RangeSource {
        var requests = 0
        override suspend fun id(): SourceId = inner.id()
        override suspend fun readTail(n: Int): ByteView = inner.readTail(n).also { requests++ }
        override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = inner.readRanges(ranges).also { requests += ranges.size }
    }

    /** The rows as the earlier reader built them: every column whole, then filtered row by row. */
    private fun referenceRows(bytes: ByteArray, ranges: List<LongRange>?): List<CellRow> {
        val file = ParquetFile.wrap(bytes)
        val properties = FeedPaths.parseDictionary(file.metadata.keyValueMetadata["dictionary"])
        val h3 = file.readColumn("h3_index")
        val devices = file.readColumn("devices")
        val updated = file.readColumn("updated")
        val unit = (file.leaf("updated").logicalType as ParquetLogicalType.Timestamp).unit
        assertEquals(ParquetLogicalType.TimeUnit.MILLIS, unit)
        val stats = properties.associate { p -> p.id to listOf("n", "mean", "min", "max").map { file.readColumn("${p.id}__$it") } }
        return (0 until h3.size).filter { i -> !h3.isNull(i) && (ranges == null || H3Index.inRanges(h3.asLong(i)!!, ranges)) }.map { i ->
            val cell = LinkedHashMap<String, CellStat>()
            for ((id, cols) in stats) {
                val stat = CellStat(n = cols[0].asLong(i)?.toInt(), mean = cols[1].asDouble(i), min = cols[2].asDouble(i), max = cols[3].asDouble(i))
                if (stat.mean != null || stat.n != null) cell[id] = stat
            }
            CellRow(h3 = h3.asLong(i)!!, devices = devices.asLong(i)!!.toInt(), updatedEpochMs = updated.asLong(i)!!, stats = cell)
        }
    }
}
