package earth.walkthru.opensensor.designsystem

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
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

    private fun size(preferredPx: Float, oneScalePx: Float, trial: (Float) -> FitTrial): Float {
        val choice = chooseFit(preferredPx, oneScalePx, singleLine = true, trial = trial)
        assertTrue(choice.fits, "$choice")
        return choice.px
    }

    @Test
    fun theLimitsAreTheOwnersNumbers() {
        // Literals on purpose, so changing a constant fails here and gets a second look.
        assertEquals(0.85f, FitTextLimits.FloorFraction)
        assertEquals(0.5f, FitTextLimits.MinFraction)
        assertEquals(0.02f, FitTextLimits.StepFraction)
        assertEquals(2, FitTextLimits.LabelMaxLines)
    }

    @Test
    fun aLabelThatFitsKeepsTheUsersSize() {
        assertEquals(60f, size(preferredPx = 60f, oneScalePx = 24f, trial = label(5f, 400f)))
    }

    @Test
    fun aLargeTextLabelGetsTheLargestOneLineSizeAboveItsOneScaleSize() {
        // Fits on one line up to 40 px. The grid steps by 2 percent of 24 px from 60 px.
        val size = size(60f, 24f, label(5f, 200f))
        assertTrue(size <= 40f && size > 40f - 24f * 0.02f, "$size")
    }

    @Test
    fun aLabelShrinksBelowItsOneScaleSizeOnlyToTheFloor() {
        // Fits on one line only up to 22 px, between the 20.4 px floor and the 24 px 1.0 size.
        val size = size(60f, 24f, label(5f, 110f))
        assertTrue(size in 20.4f..22f, "$size")
    }

    @Test
    fun belowTheFloorALabelWrapsAtTheFloorInsteadOfShrinking() {
        // One line needs 10 px, far under the floor. Wrapping between words works at any size.
        assertEquals(20.4f, size(60f, 24f, label(5f, 50f)), 0.01f)
    }

    @Test
    fun aWordTooWideAtTheFloorShrinksUntilItIsWholeDownToHalfItsOneScaleSize() {
        val size = size(60f, 24f, label(5f, 50f, wordSafeBelow = 18f))
        assertTrue(size <= 18f && size > 18f - 0.5f, "$size")
        assertEquals(12f, size(60f, 24f, label(5f, 50f, wordSafeBelow = 12f)), 0.01f)
    }

    @Test
    fun aWordTooWideAtHalfSizeGivesUpForOneLineWithAnEllipsis() {
        val hopeless = chooseFit(60f, 24f, singleLine = true, trial = label(5f, 50f, wordSafeBelow = 11f))
        assertEquals(FitChoice(12f, fits = false), hopeless)
    }

    @Test
    fun bodyTextKeepsItsSizeUnlessAWordIsWiderThanTheLine() {
        val wraps = { size: Float -> FitTrial(lines = 4, overflow = false, midWordBreak = size > 30f) }
        assertEquals(FitChoice(60f, true), chooseFit(60f, 24f, singleLine = false) { FitTrial(4, false, false) })
        val size = chooseFit(60f, 24f, singleLine = false, trial = wraps).px
        assertTrue(size <= 30f && size > 30f - 0.5f - 24f * 0.02f, "$size")
        assertEquals(FitChoice(12f, fits = false), chooseFit(60f, 24f, singleLine = false) { FitTrial(4, false, true) })
    }

    @Test
    fun belowFontScaleOneTheFloorFollowsTheSmallerSize() {
        assertEquals(17f, size(20f, 24f, label(5f, 50f)), 0.01f)
    }

    @Test
    fun aDisplayNumeralsOneScaleSizeIsItsTokenNotItsClampedSize() {
        for (scale in listOf(1f, 1.3f, 2f, 3.1f)) {
            val type = brandTypography(FontFamily.Default, FontFamily.Default, Density(2f, scale))
            assertEquals(84.sp, type.oneScaleSize(type.displayLarge), "displayLarge at $scale")
            assertEquals(48.sp, type.oneScaleSize(type.displayMedium), "displayMedium at $scale")
            assertEquals(32.sp, type.oneScaleSize(type.displaySmall), "displaySmall at $scale")
            assertEquals(type.titleSmall.fontSize, type.oneScaleSize(type.titleSmall), "titleSmall at $scale")
            val plain = type.bodyMedium.copy(fontSize = type.displaySmall.fontSize)
            assertEquals(plain.fontSize, type.oneScaleSize(plain), "a plain style of the same sp keeps it at $scale")
        }
    }

    @Test
    fun aLineEndInsideAWordIsAMidWordBreak() {
        assertTrue(breaksInsideWord("أجهزتي", listOf(5)))
        assertTrue(breaksInsideWord("History", listOf(5)))
        assertTrue(breaksInsideWord("AQ-7500", listOf(5)))
        // A hyphen between letters or digits joins one word.
        assertTrue(breaksInsideWord("AQ-7500", listOf(3)))
        assertTrue(breaksInsideWord("Wi-Fi on", listOf(3)))
        assertFalse(breaksInsideWord("PM2.5 / PM10", listOf(7)))
        assertFalse(breaksInsideWord("10–20", listOf(3)))
        assertFalse(breaksInsideWord("My devices", listOf(3)))
        assertFalse(breaksInsideWord("My devices", listOf(2)))
        assertFalse(breaksInsideWord("one line", emptyList()))
        assertFalse(breaksInsideWord("تظهر الإشعارات", listOf(5)))
    }
}
