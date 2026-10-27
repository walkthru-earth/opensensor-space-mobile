package space.opensensor.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocationAreaTest {
    @Test
    fun everyPrecisionReadsAsARoundedArea() {
        val labels = (0..15).map { LocationArea.label(it) }.map { "${it.value} ${it.unit}" }
        assertEquals(
            listOf(
                "4,400,000 KM2", "610,000 KM2", "87,000 KM2", "12,000 KM2", "1,800 KM2", "250 KM2", "36 KM2", "5.2 KM2",
                "0.74 KM2", "0.11 KM2", "15,000 M2", "2,100 M2", "310 M2", "44 M2", "6.3 M2", "0.9 M2",
            ),
            labels,
        )
    }

    @Test
    fun theDefaultMatchesTheInfoSheet() {
        // location_precision_info says the default covers about 0.74 km².
        assertEquals(LocationArea.Label("0.74", LocationArea.Unit.KM2), LocationArea.label(DeviceLocationDraft.DEFAULT_RESOLUTION))
    }

    @Test
    fun twoSignificantDigitsKeepWholeNumbersAndDropTrailingZeros() {
        assertEquals("10", LocationArea.twoDigits(9.96))
        assertEquals("1.5", LocationArea.twoDigits(1.49))
        assertEquals("0.05", LocationArea.twoDigits(0.0504))
        assertEquals("1,000", LocationArea.twoDigits(999.9))
    }

    @Test
    fun precisionOutsideH3IsRejected() {
        assertFailsWith<IllegalArgumentException> { LocationArea.label(16) }
        assertFailsWith<IllegalArgumentException> { LocationArea.label(-1) }
    }
}
