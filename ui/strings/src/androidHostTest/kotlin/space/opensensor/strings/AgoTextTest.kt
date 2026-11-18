package space.opensensor.strings

import kotlin.test.Test
import kotlin.test.assertEquals

class AgoTextTest {
    @Test
    fun agoPicksTheLargestWholeUnit() {
        assertEquals(AgoUnit.JUST_NOW to 0L, agoParts(-5))
        assertEquals(AgoUnit.SECONDS to 12L, agoParts(12_400))
        assertEquals(AgoUnit.SECONDS to 59L, agoParts(59_999))
        assertEquals(AgoUnit.MINUTES to 1L, agoParts(60_000))
        assertEquals(AgoUnit.MINUTES to 2L, agoParts(150_000))
        assertEquals(AgoUnit.HOURS to 3L, agoParts(3 * 3_600_000L + 1))
        assertEquals(AgoUnit.DAYS to 2L, agoParts(2 * 86_400_000L))
    }

    @Test
    fun fillCountTakesEitherPlaceholder() {
        assertEquals("12 s ago", fillCount("%1\$d s ago", 12))
        assertEquals("قبل 4 د", fillCount("قبل %1\$d د", 4))
        assertEquals("5 min", fillCount("%1\$s min", 5))
    }
}
