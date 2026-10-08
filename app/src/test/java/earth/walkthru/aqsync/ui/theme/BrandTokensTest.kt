package earth.walkthru.aqsync.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import earth.walkthru.aqsync.data.history.AirMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the brand tokens to the website registry (`lib/brand.ts`, `sensing`) and checks that every
 * text role and every ink on an air-quality band stays readable. Update the expected values only
 * together with the website.
 */
class BrandTokensTest {
    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun roles(p: BrandPalette) = listOf(p.main, p.ink, p.paper, p.surface, p.accent, p.deep, p.text, p.muted, p.border).map(::hex)

    @Test fun lightMatchesWebsiteRegistry() {
        assertEquals(
            listOf("#71B8F4", "#102F48", "#F4FAFF", "#DFEEF9", "#9ADDE7", "#205A80", "#102F48", "#44647B", "#A4C6DE"),
            roles(BrandTokens.Light),
        )
    }

    @Test fun darkMatchesWebsiteRegistry() {
        assertEquals(
            listOf("#71B8F4", "#102F48", "#131F2A", "#1D2E3C", "#9ADDE7", "#205A80", "#EEF8FF", "#A9C4D8", "#41617A"),
            roles(BrandTokens.Dark),
        )
    }

    @Test fun bandsAreUnchangedScientificColours() {
        assertEquals(
            listOf("#4CC38A", "#E9C46A", "#F4A261", "#E76F51", "#9B2226"),
            AirMood.entries.map { hex(it.color()) },
        )
    }

    @Test fun textRolesReachBodyContrastOnBothThemes() {
        for (scheme in listOf(BrandLightScheme, BrandDarkScheme)) {
            val pairs = listOf(
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
                "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            )
            for ((name, pair) in pairs) {
                val ratio = contrastRatio(pair.first, pair.second)
                assertTrue("$name is $ratio", ratio >= BODY_TEXT_CONTRAST)
            }
            // Outlines of interactive controls need 3:1 (WCAG 1.4.11).
            assertTrue(contrastRatio(scheme.outline, scheme.surface) >= LARGE_TEXT_CONTRAST)
        }
    }

    @Test fun inkOnEveryBandIsReadable() {
        for (mood in AirMood.entries) {
            val ratio = contrastRatio(mood.onColor(), mood.color())
            assertTrue("${mood.name} ink contrast $ratio", ratio >= BODY_TEXT_CONTRAST)
        }
    }

    @Test fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(BandGood, BandGood), 0.0001)
    }
}
