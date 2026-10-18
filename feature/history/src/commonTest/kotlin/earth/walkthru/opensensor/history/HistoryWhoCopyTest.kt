package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Simple never names WHO. The guideline line keeps its label in plain words there. */
class HistoryWhoCopyTest {
    private val detailedLevels = DetailLevel.entries.filter { it != DetailLevel.SIMPLE }

    @Test
    fun theGuidelineLabelFollowsTheLevel() {
        // The label and its shorter fallbacks, longest first. Simple never falls back to a WHO label.
        assertEquals(listOf("chart_guideline_24h", "chart_guideline_short"), guidelineLabelsRes(DetailLevel.SIMPLE).map { it.key })
        detailedLevels.forEach { assertEquals(listOf("chart_who_24h", "chart_guideline_24h", "chart_guideline_short"), guidelineLabelsRes(it).map { r -> r.key }, "$it") }
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
