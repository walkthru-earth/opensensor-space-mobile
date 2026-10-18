package earth.walkthru.opensensor.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The size search behind FitText and BodyText, with a fake layout. A text is [k] px wide per px of
 * font size and a line is [width] px wide, so it fits on one line while `k * size <= width`.
 */
class FitTextTest {
    private fun label(k: Float, width: Float, wordSafeBelow: Float = Float.MAX_VALUE) = { size: Float ->
        val lines = if (k * size <= width) 1 else 2
        FitTrial(lines = lines, overflow = false, midWordBreak = lines > 1 && size > wordSafeBelow)
    }

    @Test
    fun aLabelThatFitsKeepsTheUsersSize() {
        assertEquals(60f, chooseFitSizePx(preferredPx = 60f, oneScalePx = 24f, singleLine = true, trial = label(5f, 400f)))
    }

    @Test
    fun aLargeTextLabelGetsTheLargestOneLineSizeAboveItsOneScaleSize() {
        // Fits on one line up to 40 px. The grid steps by 2 percent of 24 px from 60 px.
        val size = chooseFitSizePx(60f, 24f, singleLine = true, trial = label(5f, 200f))
        assertTrue(size <= 40f && size > 40f - 24f * FitTextLimits.StepFraction, "$size")
    }

    @Test
    fun aLabelShrinksBelowItsOneScaleSizeOnlyToTheFloor() {
        // Fits on one line only up to 22 px, between the 20.4 px floor and the 24 px 1.0 size.
        val size = chooseFitSizePx(60f, 24f, singleLine = true, trial = label(5f, 110f))
        assertTrue(size in 24f * FitTextLimits.FloorFraction..22f, "$size")
    }

    @Test
    fun belowTheFloorALabelWrapsAtTheFloorInsteadOfShrinking() {
        // One line needs 10 px, far under the floor. Wrapping between words works at any size.
        val size = chooseFitSizePx(60f, 24f, singleLine = true, trial = label(5f, 50f))
        assertEquals(24f * FitTextLimits.FloorFraction, size, 0.01f)
    }

    @Test
    fun aSingleWordTooWideAtTheFloorShrinksUntilItIsWholeButNotPastTheLastResort() {
        val size = chooseFitSizePx(60f, 24f, singleLine = true, trial = label(5f, 50f, wordSafeBelow = 18f))
        assertTrue(size <= 18f && size >= 24f * FitTextLimits.LastResortFraction, "$size")
        val hopeless = chooseFitSizePx(60f, 24f, singleLine = true, trial = label(5f, 50f, wordSafeBelow = 1f))
        assertEquals(24f * FitTextLimits.LastResortFraction, hopeless, 0.01f)
    }

    @Test
    fun bodyTextKeepsItsSizeUnlessAWordIsWiderThanTheLine() {
        val wraps = { size: Float -> FitTrial(lines = 4, overflow = false, midWordBreak = size > 30f) }
        assertEquals(60f, chooseFitSizePx(60f, 24f, singleLine = false) { FitTrial(4, false, false) })
        val size = chooseFitSizePx(60f, 24f, singleLine = false, trial = wraps)
        assertTrue(size <= 30f && size > 30f - 0.5f - 24f * FitTextLimits.StepFraction, "$size")
    }

    @Test
    fun belowFontScaleOneTheFloorFollowsTheSmallerSize() {
        val size = chooseFitSizePx(20f, 24f, singleLine = true, trial = label(5f, 50f))
        assertEquals(20f * FitTextLimits.FloorFraction, size, 0.01f)
    }

    @Test
    fun aLineEndInsideAWordIsAMidWordBreak() {
        assertTrue(breaksInsideWord("أجهزتي", listOf(5)))
        assertTrue(breaksInsideWord("History", listOf(5)))
        assertTrue(breaksInsideWord("AQ-7500", listOf(5)))
        assertFalse(breaksInsideWord("My devices", listOf(3)))
        assertFalse(breaksInsideWord("My devices", listOf(2)))
        assertFalse(breaksInsideWord("Wi-Fi on", listOf(3)))
        assertFalse(breaksInsideWord("one line", emptyList()))
        assertFalse(breaksInsideWord("تظهر الإشعارات", listOf(5)))
    }
}
