package space.opensensor.publicfeed

import com.uber.h3core.H3Core
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** [H3Index.isCell] answers like the H3 library's `isValidCell` on cells, pentagons and noise. */
class H3IsCellTest {
    private val h3 = H3Core.newInstance()

    @Test
    fun isCellMatchesTheH3Library() {
        val random = Random(7)
        val cells = ArrayList<Long>()
        for (res in 0..H3Index.MAX_RES) {
            cells += h3.getPentagons(res)
            repeat(40) { cells += h3.latLngToCell(random.nextDouble(-90.0, 90.0), random.nextDouble(-180.0, 180.0), res) }
        }
        val candidates = cells.flatMap { cell ->
            // Every single bit flipped, so each field and each digit is pushed out of range once.
            listOf(cell) + (0 until 64).map { bit -> cell xor (1L shl bit) }
        } + List(2_000) { random.nextLong() }
        for (candidate in candidates) {
            assertEquals(h3.isValidCell(candidate), H3Index.isCell(candidate), H3Index.toHex(candidate))
        }
    }

    @Test
    fun theDeletedSubsequenceOfAPentagonIsNoCell() {
        val pentagon = h3.getPentagons(1).first()
        val kAxis = (pentagon and (7L shl 42).inv()) or (1L shl 42)
        assertFalse(h3.isValidCell(kAxis))
        assertFalse(H3Index.isCell(kAxis))
    }
}
