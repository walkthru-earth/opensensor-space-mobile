package earth.walkthru.aqsync.publicfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vectors from Python `h3` 4.5.0 (tools/discover-fixture uses the same version). */
class H3IndexTest {
    private val berlin7 = 608533319822344191L // 871f1d489ffffff
    private val berlin4 = 595022527214911487L // 841f1d5ffffffff

    @Test
    fun `resolution and hex form`() {
        assertEquals(7, H3Index.resolution(berlin7))
        assertEquals("871f1d489ffffff", H3Index.toHex(berlin7))
        assertEquals(berlin7, H3Index.fromHex("871f1d489ffffff"))
    }

    @Test
    fun `parent matches h3`() {
        assertEquals(berlin4, H3Index.parent(berlin7, 4))
        assertEquals(berlin7, H3Index.parent(berlin7, 7))
    }

    @Test
    fun `descendant range of a hexagon is exactly its children`() {
        // h3.cell_to_children(841f1d5ffffffff, 7): 343 cells, min and max below.
        assertEquals(608533317523865599L..608533324872286207L, H3Index.descendantRange(berlin4, 7))
        assertTrue(berlin7 in H3Index.descendantRange(berlin4, 7))
    }

    @Test
    fun `descendant range of a pentagon holds its children`() {
        // Pentagon 820807fffffffff, children at res 4 span 594615896891195391..594616360747663359.
        val range = H3Index.descendantRange(585609238802333695L, 4)
        assertTrue(594615896891195391L in range)
        assertTrue(594616360747663359L in range)
        assertEquals(594616360747663359L, range.last)
    }

    @Test
    fun `merge sorts and joins touching ranges`() {
        assertEquals(listOf(1L..7L, 10L..12L), H3Index.merge(listOf(10L..12L, 4L..7L, 1L..3L, 5L..6L)))
        assertTrue(H3Index.merge(emptyList()).isEmpty())
    }

    @Test
    fun `range lookups`() {
        val ranges = listOf(1L..3L, 10L..12L, 20L..20L)
        assertTrue(H3Index.inRanges(11, ranges))
        assertTrue(H3Index.inRanges(20, ranges))
        assertFalse(H3Index.inRanges(5, ranges))
        assertTrue(H3Index.overlaps(4, 10, ranges))
        assertFalse(H3Index.overlaps(4, 9, ranges))
        assertFalse(H3Index.overlaps(21, 30, ranges))
    }

    @Test
    fun `zoom to resolution follows the website`() {
        assertEquals(2, ViewportCover.resolutionForZoom(0.0))
        assertEquals(2, ViewportCover.resolutionForZoom(3.0))
        assertEquals(4, ViewportCover.resolutionForZoom(5.0))
        assertEquals(6, ViewportCover.resolutionForZoom(7.5))
        assertEquals(7, ViewportCover.resolutionForZoom(9.0))
        assertEquals(7, ViewportCover.resolutionForZoom(18.0))
    }

    @Test
    fun `viewport normalisation splits the antimeridian and gives up on the world`() {
        assertNull(ViewportCover.normalise(GeoBounds(-180.0, -60.0, 180.0, 60.0)))
        val crossing = ViewportCover.normalise(GeoBounds(170.0, -10.0, 190.0, 10.0))!!
        assertEquals(listOf(GeoBounds(170.0, -10.0, 180.0, 10.0), GeoBounds(-180.0, -10.0, -170.0, 10.0)), crossing)
        val wrapped = ViewportCover.normalise(GeoBounds(370.0, 0.0, 380.0, 1.0))!!
        assertEquals(listOf(GeoBounds(10.0, 0.0, 20.0, 1.0)), wrapped)
    }
}
