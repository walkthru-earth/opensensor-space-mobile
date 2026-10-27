package space.opensensor.history

import androidx.compose.ui.unit.dp
import space.opensensor.airquality.WhoStep
import space.opensensor.designsystem.BrandDarkScheme
import space.opensensor.designsystem.BrandLightScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #119. The ring and the calendar never tell WHO steps apart by colour alone, and month days are full touch targets. */
class WhoCueTest {
    @Test
    fun eachWhoStepFillsMoreOfItsMarkThanTheOneBefore() {
        val shares = WhoStep.entries.map(::whoStepShare)
        assertTrue(shares.zipWithNext().all { (a, b) -> b > a }, "shares $shares")
        assertTrue(shares.first() >= 0.3f, "the cleanest step stays visible")
        assertEquals(1f, shares.last())
    }

    @Test
    fun aMetricWithoutAWhoScaleFillsTheWholeMark() {
        assertEquals(1f, whoStepShare(null))
    }

    @Test
    fun theMarkBorderKeepsThreeToOneOnEveryCardSurface() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            for (surface in listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh)) {
                val ratio = contrastRatio(scheme.outline, surface)
                assertTrue(ratio >= 3.0, "outline on $surface is $ratio")
            }
        }
    }

    @Test
    fun monthRowsAreFullTouchTargetsAndAYearStaysCompact() {
        val gridTop = 16.dp
        assertEquals(gridTop + 48.dp * 7, calendarHeight(weeks = 5, gridTop = gridTop))
        assertEquals(gridTop + 48.dp * 7, calendarHeight(weeks = 6, gridTop = gridTop))
        assertEquals(120.dp, calendarHeight(weeks = 53, gridTop = gridTop))
    }
}
