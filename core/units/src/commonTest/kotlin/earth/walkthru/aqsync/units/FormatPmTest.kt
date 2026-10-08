package earth.walkthru.aqsync.units

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatPmTest {
    @Test
    fun belowOneHundredKeepsOneDecimal() {
        assertEquals("12.3", formatPm(12.34))
        assertEquals("0.5", formatPm(0.46))
        assertEquals("37.5", formatPm(37.5))
    }

    @Test
    fun trailingZeroDecimalIsDropped() {
        assertEquals("9", formatPm(9.0))
        assertEquals("0", formatPm(0.0))
        assertEquals("15", formatPm(15.04))
    }

    @Test
    fun fromOneHundredUpIsWhole() {
        assertEquals("172", formatPm(172.0))
        assertEquals("172", formatPm(172.4))
        assertEquals("100", formatPm(100.0))
        assertEquals("100", formatPm(99.96))
    }
}
