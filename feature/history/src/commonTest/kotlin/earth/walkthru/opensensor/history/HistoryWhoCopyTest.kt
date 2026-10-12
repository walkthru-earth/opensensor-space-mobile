package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Simple never names WHO. The guideline line keeps its label in plain words there. */
class HistoryWhoCopyTest {
    private val detailedLevels = DetailLevel.entries.filter { it != DetailLevel.SIMPLE }

    @Test
    fun theGuidelineLabelFollowsTheLevel() {
        assertEquals("chart_guideline_24h", guidelineLabelRes(DetailLevel.SIMPLE).key)
        detailedLevels.forEach { assertEquals("chart_who_24h", guidelineLabelRes(it).key, "$it") }
    }

    @Test
    fun theGuidelineNoteFollowsTheLevel() {
        assertEquals("chart_guideline_note_simple", guidelineNoteRes(DetailLevel.SIMPLE).key)
        detailedLevels.forEach { assertEquals("chart_guideline_note", guidelineNoteRes(it).key, "$it") }
    }

    @Test
    fun theColourInfoFollowsTheLevel() {
        assertEquals("info_history_body5_simple", colourInfoRes(DetailLevel.SIMPLE).key)
        detailedLevels.forEach { assertEquals("info_history_body5", colourInfoRes(it).key, "$it") }
    }
}
