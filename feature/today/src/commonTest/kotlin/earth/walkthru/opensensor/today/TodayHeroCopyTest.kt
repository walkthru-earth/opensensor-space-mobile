package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Simple speaks in moods, so the hero line and its info sheet never pick the guideline wording there. */
class TodayHeroCopyTest {
    private val detailedLevels = DetailLevel.entries.filter { it.detailed }

    @Test
    fun simpleHeroLineIsTheMoodLine() {
        val expected = listOf("mood_fresh_line", "mood_fair_line", "mood_hazy_line", "mood_poor_line", "mood_smoky_line", "mood_thick_line")
        assertEquals(expected, AirMood.entries.map { heroLineRes(it, DetailLevel.SIMPLE).key })
    }

    @Test
    fun detailedHeroLineIsTheAdvice() {
        val expected = listOf("mood_fresh_advice", "mood_fair_advice", "mood_hazy_advice", "mood_poor_advice", "mood_smoky_advice", "mood_thick_advice")
        detailedLevels.forEach { level -> assertEquals(expected, AirMood.entries.map { heroLineRes(it, level).key }, "$level") }
    }

    @Test
    fun nowInfoBodyFollowsTheLevel() {
        assertEquals("info_now_body_simple", nowInfoBodyRes(DetailLevel.SIMPLE).key)
        detailedLevels.forEach { assertEquals("info_now_body", nowInfoBodyRes(it).key, "$it") }
    }
}
