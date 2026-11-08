package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vectors from Python `h3` 4.5.0. `H3IsCellTest` in `:core:publicfeed` holds [H3Cell.isCell] to the library. */
class H3CellTest {
    @Test
    fun `canonical hex parses at any resolution or the one asked for`() {
        assertEquals(0x8828308281fffffL, H3Cell.parseHex("8828308281fffff"))
        assertEquals(8, H3Cell.resolution(H3Cell.parseHex("8828308281fffff")!!))
        assertTrue(H3Cell.isCellHex("8828308281fffff", H3Cell.RESOLUTION))
        assertTrue(H3Cell.isCellHex("85283083fffffff"))
        assertFalse(H3Cell.isCellHex("85283083fffffff", 8))
        assertFalse(H3Cell.isCellHex("8928308280fffff", 8))
    }

    @Test
    fun `anything but a canonical cell is refused`() {
        for (text in listOf("", "8828308281FFFFF", "08828308281fffff", "828308281fffff", " 8828308281fffff", "887576970a3ffff", "zz28308281fffff")) {
            assertNull(H3Cell.parseHex(text), text)
        }
    }
}
