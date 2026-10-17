package earth.walkthru.opensensor.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorContrastTest {
    private fun hex(c: Color): String {
        val rgb = c.toArgb() and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }

    private fun roles(p: BrandPalette) =
        listOf(p.main, p.ink, p.paper, p.surface, p.accent, p.deep, p.text, p.muted, p.border).map(::hex)

    @Test
    fun lightAndDarkPalettesMatchWebsiteRegistry() {
        assertEquals(
            listOf("#71B8F4", "#102F48", "#F4FAFF", "#DFEEF9", "#9ADDE7", "#205A80", "#102F48", "#44647B", "#A4C6DE"),
            roles(BrandTokens.Light),
        )
        assertEquals(
            listOf("#71B8F4", "#102F48", "#131F2A", "#1D2E3C", "#9ADDE7", "#205A80", "#EEF8FF", "#A9C4D8", "#41617A"),
            roles(BrandTokens.Dark),
        )
    }

    @Test
    fun textRolesReachBodyContrastOnLightAndDarkThemes() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val pairs = listOf(
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onSurface/surfaceContainerLow" to (scheme.onSurface to scheme.surfaceContainerLow),
                "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                "onSurface/surfaceContainerHigh" to (scheme.onSurface to scheme.surfaceContainerHigh),
                "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
                "onSurfaceVariant/surfaceContainerLow" to (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
                "onSurfaceVariant/surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            )
            for ((name, pair) in pairs) {
                val ratio = contrastRatio(pair.first, pair.second)
                assertTrue(ratio >= BODY_TEXT_CONTRAST, "$name contrast $ratio < $BODY_TEXT_CONTRAST")
            }
        }
    }

    @Test
    fun functionalOutlinesAndSwatchBordersReachUiContrast() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val surfaces = listOf(
                scheme.surface,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
            )
            for (bg in surfaces) {
                val outlineRatio = contrastRatio(scheme.outline, bg)
                assertTrue(outlineRatio >= UI_COMPONENT_CONTRAST, "outline contrast $outlineRatio < $UI_COMPONENT_CONTRAST")
                val swatchRatio = contrastRatio(swatchBorderColor(scheme), bg)
                assertTrue(swatchRatio >= UI_COMPONENT_CONTRAST, "swatchBorder contrast $swatchRatio < $UI_COMPONENT_CONTRAST")
            }
        }
    }

    @Test
    fun signalBarColoursReachUiContrastInLightDarkAndSelectedStates() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val surfaces = listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
            for (bg in surfaces) {
                val filledRatio = contrastRatio(signalBarColor(filled = true, onSelected = false, scheme = scheme), bg)
                val unfilledRatio = contrastRatio(signalBarColor(filled = false, onSelected = false, scheme = scheme), bg)
                assertTrue(filledRatio >= UI_COMPONENT_CONTRAST, "filled signal bar contrast $filledRatio")
                assertTrue(unfilledRatio >= UI_COMPONENT_CONTRAST, "unfilled signal bar contrast $unfilledRatio")
            }
            val selectedFilled = contrastRatio(
                signalBarColor(filled = true, onSelected = true, scheme = scheme),
                FloatingControl.Selected,
            )
            val selectedUnfilled = contrastRatio(
                signalBarColor(filled = false, onSelected = true, scheme = scheme),
                FloatingControl.Selected,
            )
            assertTrue(selectedFilled >= UI_COMPONENT_CONTRAST, "selected filled signal bar contrast $selectedFilled")
            assertTrue(selectedUnfilled >= UI_COMPONENT_CONTRAST, "selected unfilled signal bar contrast $selectedUnfilled")
        }
    }

    @Test
    fun whoStepsAndAirMoodsMaintainReadableInksAndSurfaceFallbacks() {
        val lum = WhoStep.entries.map { relativeLuminance(it.color()) }
        assertEquals(lum.sortedDescending(), lum, "WHO steps run light to dark for greyscale legibility")
        for (mood in AirMood.entries) {
            assertEquals(mood.step.color(), mood.color())
            assertEquals(mood.step.onColor(), mood.onColor())
            val inkRatio = contrastRatio(mood.onColor(), mood.color())
            assertTrue(inkRatio >= BODY_TEXT_CONTRAST, "${mood.name} ink contrast $inkRatio")
            for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
                val textColor = mood.textColorOnSurface(scheme.surface, scheme.onSurface, BODY_TEXT_CONTRAST)
                val textRatio = contrastRatio(textColor, scheme.surface)
                assertTrue(textRatio >= BODY_TEXT_CONTRAST, "${mood.name} text on surface contrast $textRatio")
            }
        }
    }
}
