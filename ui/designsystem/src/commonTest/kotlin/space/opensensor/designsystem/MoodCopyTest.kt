package space.opensensor.designsystem

import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import kotlin.test.Test
import kotlin.test.assertEquals

class MoodCopyTest {
    @Test
    fun eachStepHasItsWordAndLine() {
        assertEquals(
            listOf("mood_fresh", "mood_fair", "mood_hazy", "mood_poor", "mood_smoky", "mood_thick"),
            WhoStep.entries.map { moodWordRes(it).key },
        )
        assertEquals("mood_none", moodWordRes(null).key)
        assertEquals(
            listOf("mood_fresh_line", "mood_fair_line", "mood_hazy_line", "mood_poor_line", "mood_smoky_line", "mood_thick_line"),
            WhoStep.entries.map { moodLineRes(it).key },
        )
        assertEquals("mood_none_line", moodLineRes(null).key)
    }

    @Test
    fun thePeacockFollowsTheStep() {
        assertEquals(
            listOf(
                PeacockExpression.HAPPY,
                PeacockExpression.CALM,
                PeacockExpression.CONCERNED,
                PeacockExpression.MASKED,
                PeacockExpression.MASKED,
                PeacockExpression.FEATHER_SHIELD,
            ),
            WhoStep.entries.map { peacockExpressionFor(it) },
        )
        assertEquals(PeacockExpression.CONFUSED, peacockExpressionFor(null as WhoStep?))
        assertEquals(
            listOf(
                PeacockExpression.HAPPY,
                PeacockExpression.CALM,
                PeacockExpression.CONCERNED,
                PeacockExpression.MASKED,
                PeacockExpression.MASKED,
                PeacockExpression.FEATHER_SHIELD,
            ),
            AirMood.entries.map { peacockExpressionFor(it) },
        )
    }

    @Test
    fun theStripSummaryReadsEachBlockWithItsCaption() {
        assertEquals(
            "Fresh air 7 days, n/a 24 hours, Poor 1 hour",
            moodSummary(listOf("Fresh air", "n/a", "Poor"), listOf("7 days", "24 hours", "1 hour")),
        )
        assertEquals("Fresh air, Poor", moodSummary(listOf("Fresh air", "Poor"), emptyList()))
    }

    @Test
    fun theSummariesJoinWithTheLocalisedSeparator() {
        assertEquals("هواء نقي 7 أيام، سيئ ساعة", moodSummary(listOf("هواء نقي", "سيئ"), listOf("7 أيام", "ساعة"), separator = "،"))
        assertEquals("PM2.5. هواء نقي، مقبول", legendSummary(listOf("هواء نقي", "مقبول"), "PM2.5", separator = "،"))
        assertEquals("a، b", spokenList(listOf("a", "b"), "،"))
    }

    @Test
    fun theLegendSummaryNamesTheTitleThenTheWords() {
        assertEquals("PM2.5. Fresh air, Fair", legendSummary(listOf("Fresh air", "Fair"), "PM2.5"))
        assertEquals("Fresh air, Fair", legendSummary(listOf("Fresh air", "Fair"), null))
    }

    @Test
    fun blockWidthsFollowWeightsWithoutAMinimum() {
        assertEquals(listOf(100f, 200f), blockWidths(listOf(1f, 2f), available = 300f, min = 0f))
    }

    @Test
    fun aSmallBlockStillGetsItsMinimumTouchWidth() {
        val widths = blockWidths(listOf(1f, 99f), available = 300f, min = 48f)
        assertEquals(48f, widths[0])
        assertEquals(252f, widths[1])
        // A second small block is lifted too, the rest keep their proportion.
        val three = blockWidths(listOf(1f, 2f, 97f), available = 300f, min = 48f)
        assertEquals(listOf(48f, 48f, 204f), three)
    }

    @Test
    fun tooManyBlocksForTheMinimumShareTheWidthEqually() {
        assertEquals(listOf(50f, 50f, 50f), blockWidths(listOf(1f, 5f, 9f), available = 150f, min = 48f * 2))
    }

    @Test
    fun anUnboundedStripFallsBackToAFixedWidthPerBlock() {
        assertEquals(listOf(64f, 64f, 64f), blockWidths(listOf(1f, 5f, 9f), available = Float.POSITIVE_INFINITY, min = 48f))
        assertEquals(listOf(64f), blockWidths(listOf(1f), available = Float.POSITIVE_INFINITY, min = 0f))
    }
}
