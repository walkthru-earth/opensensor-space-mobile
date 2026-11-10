package space.opensensor.data.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellTrackTest {
    // Res 8 cells, each a real H3 index, as in the STAC item tests.
    private val home = 0x887576970bfffffL
    private val away = 0x887576914bfffffL
    private val gnss = LocationSource.GNSS.wire

    private fun row(s: Long, cell: Long?, source: Int? = gnss) = TrackRow(s * 1_000_000_000L, cell, source)

    @Test
    fun consecutiveRowsInOneCellAreOneSpan() {
        val track = CellTrack.of(listOf(row(0, home), row(10, home), row(20, away), row(30, away), row(40, home)))
        assertEquals(listOf(home, away, home), track.fixes.map { it.cell })
        assertEquals(listOf(2, 2, 1), track.spans.map { it.rows })
        assertEquals(listOf(home, away), track.cells)
        assertEquals(home, track.lastFix?.cell)
        assertEquals(40_000_000_000L, track.lastFix?.firstNs)
    }

    @Test
    fun aRowWithNoLocationIsANoFixSpanAndNeverBorrowsACell() {
        val track = CellTrack.of(listOf(row(0, home), row(10, null, null), row(20, null, null), row(30, away)))
        assertEquals(listOf(home, null, away), track.spans.map { it.cell })
        assertEquals(2, track.noFixRows)
        assertNull(track.spans[1].source)
        assertEquals(listOf(home, away), track.cells)
    }

    @Test
    fun aCellWithoutASourceOrWithSourceZeroIsNoFix() {
        val track = CellTrack.of(listOf(row(0, home, null), row(10, home, 0), row(20, home, 9)))
        assertTrue(track.fixes.isEmpty())
        assertNull(track.lastFix)
        assertEquals(3, track.noFixRows)
    }

    @Test
    fun aValueThatIsNotAnH3CellIsNoFix() {
        val track = CellTrack.of(listOf(row(0, 0L), row(10, 12345L), row(20, -1L)))
        assertTrue(track.cells.isEmpty())
        assertEquals(3, track.noFixRows)
    }

    @Test
    fun theSourceSplitsASpanInOneCell() {
        val track = CellTrack.of(listOf(row(0, home, LocationSource.CONFIGURED.wire), row(10, home, gnss)))
        assertEquals(listOf(LocationSource.CONFIGURED, LocationSource.GNSS), track.spans.map { it.source })
        assertEquals(listOf(home), track.cells)
    }

    @Test
    fun rowsAreReadInTimeOrder() {
        val track = CellTrack.of(listOf(row(30, away), row(0, home), row(10, home)))
        assertEquals(listOf(home, away), track.fixes.map { it.cell })
        assertEquals(0L, track.spans.first().firstNs)
        assertEquals(10_000_000_000L, track.spans.first().lastNs)
    }

    @Test
    fun noRowsIsAnEmptyTrack() {
        assertEquals(CellTrack.EMPTY, CellTrack.of(emptyList()))
        assertEquals(0, CellTrack.EMPTY.noFixRows)
    }

    @Test
    fun theWireValuesMirrorTheFirmware() {
        assertEquals(listOf(1, 2, 3), LocationSource.entries.map { it.wire })
        assertNull(LocationSource.fromWire(0))
        assertNull(LocationSource.fromWire(null))
    }
}
