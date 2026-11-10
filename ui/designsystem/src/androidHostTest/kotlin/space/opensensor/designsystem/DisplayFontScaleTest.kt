package space.opensensor.designsystem

import android.content.res.FontScaleConverter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Display numerals under the real Android 14 and later non-linear font scale tables, read through
 * Compose's `Density(context)` as the app does, and compared with the framework's own
 * [FontScaleConverter] at every size the Android font size setting offers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayFontScaleTest {
    /** The steps of Settings, Display, Font size on Android 14 and later. */
    private val androidScales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)

    private fun densityAt(scale: Float): Density {
        RuntimeEnvironment.setFontScale(scale)
        return Density(RuntimeEnvironment.getApplication()).also { assertEquals(scale, it.fontScale) }
    }

    /** The framework's sp to dp for [scale], its non-linear table where one applies, else linear. */
    private fun frameworkDp(sp: Float, scale: Float): Float =
        FontScaleConverter.forScale(scale)?.convertSpToDp(sp) ?: (sp * scale)

    @Test
    fun displayNumeralsFollowTheRealTablesAndNeverDropBelowTheirOneScaleSize() {
        for (scale in androidScales) {
            val density = densityAt(scale)
            // Compose reads the framework table, so 14 sp body text matches it at every step.
            assertEquals(frameworkDp(14f, scale), with(density) { 14.sp.toDp().value }, 0.01f, "14 sp at $scale")

            val type = brandTypography(FontFamily.SansSerif, FontFamily.Monospace, density)
            for ((style, base) in listOf(
                type.displayLarge to DisplaySizes.LARGE,
                type.displayMedium to DisplaySizes.MEDIUM,
                type.displaySmall to DisplaySizes.SMALL,
            )) {
                val rendered = with(density) { style.fontSize.toDp().value }
                val cap = base * TypographyLimits.MaxDisplayFontScale
                val table = frameworkDp(base.toFloat(), scale)
                // The tables grow display sizes far less than 1.35x, so the table's own value shows.
                assertTrue(table < cap, "table $table dp under the cap $cap at $scale")
                assertEquals(table, rendered, 0.05f, "display $base sp at $scale")
                if (scale >= 1f) {
                    assertTrue(rendered >= base - 0.05f, "display $base sp renders $rendered dp at $scale")
                } else {
                    assertTrue(rendered < base.toFloat(), "display $base sp follows a smaller setting at $scale")
                }
            }
            // Body styles keep their sp so the system scale applies in full.
            assertEquals(14f, type.bodyMedium.fontSize.value, 0.001f)
        }
    }

    @Test
    fun theLargestStepIsTheNonLinearTableNotAStraightDoubling() {
        val density = densityAt(2f)
        with(density) {
            // 14 sp body text grows to 26 dp and 84 sp only to about 86 dp, where 2x would give 28 and 168.
            assertEquals(26f, 14.sp.toDp().value, 0.05f)
            assertEquals(85.83f, 84.sp.toDp().value, 0.05f)
        }
        val type = brandTypography(FontFamily.SansSerif, FontFamily.Monospace, density)
        assertEquals(85.83f, with(density) { type.displayLarge.fontSize.toDp().value }, 0.05f)
    }
}
