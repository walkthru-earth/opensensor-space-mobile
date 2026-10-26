package space.opensensor.designsystem

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Display numerals under the real Android 14 and later non-linear font scale tables, read through
 * Compose's `Density(context)` as the app does, at the largest system scale of 200 percent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayFontScaleTest {
    @Test
    fun displayNumeralsStayBetweenTheirOneScaleSizeAndTheCapUnderTheRealTables() {
        RuntimeEnvironment.setFontScale(2f)
        val density = Density(RuntimeEnvironment.getApplication())
        assertEquals(2f, density.fontScale)
        with(density) {
            // The table, not a straight 2x. 14 sp body text grows to 26 dp and 84 sp only to about 86 dp.
            assertEquals(26f, 14.sp.toDp().value, 0.05f)
            assertEquals(85.83f, 84.sp.toDp().value, 0.05f)
        }

        val type = brandTypography(FontFamily.SansSerif, FontFamily.Monospace, density)
        for ((style, base) in listOf(
            type.displayLarge to DisplaySizes.LARGE,
            type.displayMedium to DisplaySizes.MEDIUM,
            type.displaySmall to DisplaySizes.SMALL,
        )) {
            val rendered = with(density) { style.fontSize.toDp().value }
            val converted = with(density) { base.sp.toDp().value }
            val expected = converted.coerceIn(base.toFloat(), base * TypographyLimits.MaxDisplayFontScale)
            assertEquals(expected, rendered, 0.05f, "display $base sp renders $rendered dp")
        }
        // 84 sp converts to about 86 dp, inside the clamp, so the table's own value shows.
        assertEquals(85.83f, with(density) { type.displayLarge.fontSize.toDp().value }, 0.05f)
        // Body styles keep their sp so the system scale applies in full.
        assertEquals(14f, type.bodyMedium.fontSize.value, 0.001f)
    }
}
