package earth.walkthru.opensensor.files

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun valuesPrintLikeJavaFourSignificantDigits() {
        assertEquals("42", formatValue(42.0))
        assertEquals("-3", formatValue(-3.0))
        assertEquals("21.37", formatValue(21.3712))
        assertEquals("0.5000", formatValue(0.5))
        assertEquals("1.235e+04", formatValue(12345.6))
        assertEquals("1.500e-05", formatValue(0.000015))
        assertEquals("10.00", formatValue(9.99996))
    }

    @Test
    fun utcNanosecondsFloorBeforeTheEpoch() {
        assertEquals("2026-09-08 19:45:10Z", formatUtcNs(1_788_896_710_000_000_000L))
        assertEquals("1969-12-31 23:59:59Z", formatUtcNs(-1L))
    }

    @Test
    fun uptimeAndBytes() {
        assertEquals("2m 03s", formatMonoUs(123_000_000L))
        assertEquals("1h 02m 03s", formatMonoUs(3_723_000_000L))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
    }
}
