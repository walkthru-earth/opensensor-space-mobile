package space.opensensor.devices

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceTextTest {
    @Test
    fun agoSteps() {
        assertEquals(AgoUnit.JUST_NOW to 0L, DeviceText.ago(-5))
        assertEquals(AgoUnit.SECONDS to 59L, DeviceText.ago(59_999))
        assertEquals(AgoUnit.MINUTES to 1L, DeviceText.ago(60_000))
        assertEquals(AgoUnit.HOURS to 2L, DeviceText.ago(2 * 3_600_000L + 5))
        assertEquals(AgoUnit.DAYS to 3L, DeviceText.ago(3 * 86_400_000L))
    }

    @Test
    fun fillsPositionalSlots() {
        assertEquals("seen 5 min ago", DeviceText.fill("seen %1\$s", "5 min ago"))
        assertEquals("قبل 4 د", DeviceText.fill("قبل %1\$d د", 4L))
        assertEquals("b a", DeviceText.fill("%2\$s %1\$s", "a", "b"))
    }

    @Test
    fun utcDayAndMonth() {
        assertEquals(1 to 1, DeviceText.utcDayMonth(0))
        assertEquals(29 to 2, DeviceText.utcDayMonth(1_709_164_800)) // 2024-02-29
        assertEquals(9 to 10, DeviceText.utcDayMonth(1_791_504_000 + 86_399)) // 2026-10-09, last second
        assertEquals(31 to 12, DeviceText.utcDayMonth(-1))
    }
}
