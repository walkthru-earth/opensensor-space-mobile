package earth.walkthru.aqsync.publicfeed

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a city viewport prunes row groups and keeps only its cells`() = runTest {
        val partition = feed.latest()!!
        val berlin = GeoBounds(13.2, 52.4, 13.6, 52.65)
        val cover = ViewportCover.cover(h3, berlin, dataRes = 7)
        assertTrue(cover.coverCells in 1..ViewportCover.MAX_CELLS)
        val all = feed.cells(partition, FeedWindow.DAY, 7, ranges = null)
        val view = feed.cells(partition, FeedWindow.DAY, 7, cover.ranges)
        assertTrue(view.rowGroupsRead < view.rowGroupsTotal, "pruned ${view.rowGroupsRead} of ${view.rowGroupsTotal}")
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
        val scale = CellScale.of("pm25", rows.mapNotNull { it.mean("pm25") })
        val json = CellGeoJson.build(h3, rows, "pm25", scale) { false }
        assertTrue(json.startsWith("{\"type\":\"FeatureCollection\""))
        assertEquals(rows.size, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertEquals(rows.count { it.mean("pm25") != null }, Regex("\"color\":\"#").findAll(json).count())
    }
}
