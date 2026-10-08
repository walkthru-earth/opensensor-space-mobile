package earth.walkthru.aqsync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.MetricSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The one mapping from a WHO step to its colour. Every air-quality colour in the app (bars, the
 * calendar, the day ring, the mood face and pill) goes through here, so a value can never be named
 * one step and painted another. The ARGB values and their colour-blind checks live in
 * `:core:airquality` (`WhoStep`). Never repeat them here, and never derive them from brand tokens.
 */
fun WhoStep.color(): Color = Color(color)

/** A mood paints its WHO step. */
fun AirMood.color(): Color = step.color()

/** The step colour of [value] on [metric]'s scale, or [neutral] for a metric without one. */
fun MetricSpec.valueColor(value: Double, neutral: Color): Color = stepOf(value)?.color() ?: neutral

/** Dark ink used for text and face features drawn on a light band. */
val BandInkDark = Color(0xFF14181B)

/** Light ink used on the darkest band. */
val BandInkLight = Color(0xFFFFFFFF)

/**
 * Text or face features drawn **on** a band colour: whichever of dark or light ink contrasts more.
 * The band itself never changes; only the ink on top of it adapts (see `ContrastTest`).
 */
fun AirMood.onColor(): Color = step.onColor()

fun WhoStep.onColor(): Color = onBand(color())

fun onBand(band: Color): Color =
    if (contrastRatio(BandInkDark, band) >= contrastRatio(BandInkLight, band)) BandInkDark else BandInkLight

/**
 * A band colour used **as text** on the current surface. Large text needs 3:1 (WCAG 1.4.3); a band
 * that cannot reach it on this theme's surface (yellow on light paper, maroon on dark paper)
 * falls back to the normal text colour, and the screen shows the band as a swatch instead.
 */
@Composable
fun AirMood.textColorOnSurface(): Color {
    val surface = MaterialTheme.colorScheme.surface
    val band = color()
    return if (contrastRatio(band, surface) >= LARGE_TEXT_CONTRAST) band else MaterialTheme.colorScheme.onSurface
}

const val LARGE_TEXT_CONTRAST = 3.0
const val BODY_TEXT_CONTRAST = 4.5

/** WCAG 2 relative luminance of an opaque sRGB colour. */
fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 2 contrast ratio, 1 to 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
