package space.opensensor.history

import kotlinx.datetime.TimeZone
import space.opensensor.data.history.CellTrack
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.LocationSource
import space.opensensor.data.history.TrackRow
import space.opensensor.designsystem.BrandDarkScheme
import space.opensensor.designsystem.BrandLightScheme
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The History track (#263) from a Thing's own rows, its strip, its map shape and its colours. */
class HistoryTrackTest {
    private val home = 0x887576970bfffffL
    private val away = 0x887576914bfffffL
    private val window = HistoryWindow.endingAt(1_790_812_800L, HistoryRange.DAY, TimeZone.UTC)
    private val gnss = LocationSource.GNSS.wire
    private val configured = LocationSource.CONFIGURED.wire

    /** A row [minutes] after the window opens. */
    private fun row(minutes: Long, cell: Long?, source: Int?) = TrackRow((window.fromS + minutes * 60) * NS_PER_S, cell, source)

    private fun track(vararg rows: TrackRow) = HistoryTrack.of("t", window, CellTrack.of(rows.toList()))

    @Test
    fun aFixedThingOnItsOneConfiguredCellHasNoTrack() {
        assertNull(track(row(0, home, configured), row(10, home, configured), row(20, null, null)))
        assertNull(track())
    }

    @Test
    fun oneReportedFixIsATrackAndTwoConfiguredCellsAreToo() {
        assertNotNull(track(row(0, home, gnss)))
        assertNotNull(track(row(0, home, configured), row(10, away, configured)))
    }

    @Test
    fun cellsKeepTheirFirstVisitOrderAndTheNewestSource() {
        val t = assertNotNull(track(row(0, home, configured), row(10, away, gnss), row(20, home, gnss)))
        assertEquals(listOf(TrackCell(home, LocationSource.GNSS), TrackCell(away, LocationSource.GNSS)), t.cells)
        assertEquals(home, t.last)
        assertEquals(listOf(LocationSource.CONFIGURED, LocationSource.GNSS), t.sources)
        assertFalse(t.noFix)
    }

    @Test
    fun rowsWithNoFixAreTheirOwnBarAndNeverTakeACell() {
        val t = assertNotNull(track(row(0, home, gnss), row(5, null, null), row(10, away, gnss)))
        assertEquals(listOf(LocationSource.GNSS, null, LocationSource.GNSS), t.bars.map { it.source })
        assertTrue(t.noFix)
        assertEquals(listOf(home, away), t.cells.map { it.cell })
    }

    @Test
    fun closeSpansJoinOnTheStripAndALongGapStaysEmpty() {
        val t = assertNotNull(track(row(0, home, gnss), row(10, away, gnss), row(600, home, gnss)))
        val day = 24f * 60
        // Home runs on to Away's first row, ten minutes on. Away ends at its own row, since the next
        // span starts long after.
        assertEquals(10 / day, t.bars[0].end, 1e-6f)
        assertEquals(10 / day, t.bars[1].start, 1e-6f)
        assertEquals(10 / day, t.bars[1].end, 1e-6f)
        assertEquals(600 / day, t.bars[2].start, 1e-6f)
    }

    @Test
    fun barsStayInsideTheWindow() {
        val t = assertNotNull(track(row(-30, home, gnss), row(24 * 60 + 30, away, gnss)))
        assertEquals(0f, t.bars.first().start)
        assertEquals(1f, t.bars.last().end)
    }

    @Test
    fun theShapeFitsItsCellsAndMarksTheNewest() {
        val t = assertNotNull(track(row(0, home, gnss), row(10, away, gnss)))
        val shape = assertNotNull(HistoryTrackMap.shape(FakeGrid(mapOf(home to LatLng(5.6, -0.2), away to LatLng(5.62, -0.18))), t) { 0xFF00806F.toInt() })
        val b = assertNotNull(shape.bounds)
        assertTrue(b.west < -0.2 && b.east > -0.18 && b.south < 5.6 && b.north > 5.62)
        assertEquals(LatLng(5.62, -0.18), shape.centre)
        assertTrue("\"last\":true" in shape.geoJson && "#00806f" in shape.geoJson)
        assertFalse("thing" in shape.geoJson)
    }

    @Test
    fun cellsAcrossTheAntimeridianOpenOnTheNewestWithoutAFit() {
        val t = assertNotNull(track(row(0, home, gnss), row(10, away, gnss)))
        val shape = assertNotNull(HistoryTrackMap.shape(FakeGrid(mapOf(home to LatLng(0.0, 179.99), away to LatLng(0.0, -179.99))), t) { 0 })
        assertNull(shape.bounds)
        assertEquals(LatLng(0.0, -179.99), shape.centre)
    }

    @Test
    fun aGridWithNoOutlinesDrawsNoMap() {
        val t = assertNotNull(track(row(0, home, gnss)))
        assertNull(HistoryTrackMap.shape(FakeGrid(emptyMap()), t) { 0 })
    }

    @Test
    fun theReportedColoursReachThreeToOneOnTheCardAndDifferInLightness() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val card = scheme.surfaceContainerLow
            val colours = TrackColours.of(scheme)
            for (source in LocationSource.entries) {
                assertTrue(contrastRatio(colours.of(source), card) >= LARGE_TEXT_CONTRAST, "$source on $card")
            }
        }
        assertTrue(relativeLuminance(TrackColours.PHONE) - relativeLuminance(TrackColours.GPS) > 0.05)
    }

    /** A grid with a small square around each known centre, and nothing for any other cell. */
    private class FakeGrid(private val centres: Map<Long, LatLng>) : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = error("not used")
        override fun cellToLatLng(cell: Long): LatLng = centres.getValue(cell)
        override fun cellToBoundary(cell: Long): List<LatLng> {
            val c = centres[cell] ?: return emptyList()
            val d = 0.002
            return listOf(LatLng(c.lat - d, c.lng - d), LatLng(c.lat - d, c.lng + d), LatLng(c.lat + d, c.lng + d), LatLng(c.lat + d, c.lng - d))
        }
        override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = error("not used")
    }

    private companion object {
        const val NS_PER_S = 1_000_000_000L
    }
}
