package space.opensensor.today

import space.opensensor.family.air.AirMood
import space.opensensor.family.air.Averaging
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.QcFlags
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** Every level states the averaging basis in one line, and only the detailed levels name NowCast (#93). */
    @Test
    fun averagingLineAndNoteFollowTheBasisAndTheLevel() {
        assertEquals("now_basis_nowcast_simple", averagingLineRes(Averaging.NOWCAST, DetailLevel.SIMPLE).key)
        assertEquals("now_nowcast_note_simple", averagingNoteRes(Averaging.NOWCAST, DetailLevel.SIMPLE).key)
        detailedLevels.forEach {
            assertEquals("now_basis_nowcast", averagingLineRes(Averaging.NOWCAST, it).key, "$it")
            assertEquals("now_nowcast_note", averagingNoteRes(Averaging.NOWCAST, it).key, "$it")
        }
        DetailLevel.entries.forEach {
            assertEquals("now_basis_live", averagingLineRes(Averaging.LIVE, it).key, "$it")
            assertEquals("now_snapshot_note", averagingNoteRes(Averaging.LIVE, it).key, "$it")
        }
    }

    /** Simple hides the mood of a flagged live value, the detailed levels keep it beside the flag (#91). */
    @Test
    fun aFlaggedLiveValueHasNoMoodInSimple() {
        val flagged = NowReading.Reading(30.0, AirMood.HAZY, flags = QcFlags.FLATLINE)
        assertFalse(showsMood(flagged, DetailLevel.SIMPLE))
        detailedLevels.forEach { assertTrue(showsMood(flagged, it), "$it") }
        assertTrue(showsMood(flagged.copy(flags = QcFlags.NONE), DetailLevel.SIMPLE))
        assertTrue(showsMood(flagged.copy(averaging = Averaging.NOWCAST), DetailLevel.SIMPLE), "a NowCast is rated")
    }

    @Test
    fun qcReasonsFollowTheBitOrder() {
        assertEquals(emptyList(), qcReasonRes(QcFlags.NONE).map { it.key })
        val all = QcFlags.PM25_ABOVE_PM10 or QcFlags.FLATLINE or QcFlags.SPIKE or QcFlags.HIGH_HUMIDITY
        assertEquals(listOf("qc_pm25_above_pm10", "qc_flatline", "qc_spike", "qc_high_humidity"), qcReasonRes(all).map { it.key })
        assertEquals(listOf("qc_high_humidity"), qcReasonRes(QcFlags.HIGH_HUMIDITY).map { it.key })
    }
}
