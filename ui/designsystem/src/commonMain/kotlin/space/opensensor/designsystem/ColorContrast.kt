package space.opensensor.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Minimum WCAG 2.2 AA contrast ratio for large text (18 pt or 14 pt bold). */
const val LARGE_TEXT_CONTRAST = 3.0

/** Minimum WCAG 2.2 AA contrast ratio for functional UI components, icons, and chart/swatch boundaries. */
const val UI_COMPONENT_CONTRAST = 3.0

/** Minimum WCAG 2.2 AA contrast ratio for normal body text. */
const val BODY_TEXT_CONTRAST = 4.5

/** Dark ink used for text and features drawn on a light air-quality band. */
val BandInkDark = Color(0xFF14181B)

/** Light ink used on a dark air-quality band. */
val BandInkLight = Color(0xFFFFFFFF)

/**
 * The one mapping from a WHO step to its Compose [Color]. The ARGB values and colour-blind checks
 * live in `:family:air` ([WhoStep]).
 */
fun WhoStep.color(): Color = Color(color)

/** A mood paints its [WhoStep] colour. */
fun AirMood.color(): Color = step.color()

/** Text or icon colour drawn on top of a [WhoStep] fill, picking whichever ink contrasts more. */
fun WhoStep.onColor(): Color = onBand(color())

/** Text or icon colour drawn on top of an [AirMood] fill. */
fun AirMood.onColor(): Color = step.onColor()

/** Whichever of [BandInkDark] or [BandInkLight] has higher WCAG contrast on [band]. */
fun onBand(band: Color): Color =
    if (contrastRatio(BandInkDark, band) >= contrastRatio(BandInkLight, band)) BandInkDark else BandInkLight

/**
 * A [WhoStep] colour used as text on [surface], falling back to [onSurface] when the band cannot
 * reach [minContrast] on that surface (such as light green or yellow on light paper, or maroon on
 * dark paper).
 */
fun WhoStep.textColorOnSurface(
    surface: Color,
    onSurface: Color,
    minContrast: Double = BODY_TEXT_CONTRAST,
): Color {
    val band = color()
    return if (contrastRatio(band, surface) >= minContrast) band else onSurface
}

/** A [WhoStep] colour used as text on the current theme surface. */
@Composable
fun WhoStep.textColorOnSurface(minContrast: Double = BODY_TEXT_CONTRAST): Color =
    textColorOnSurface(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, minContrast)

/** An [AirMood] colour used as text on [surface], falling back to [onSurface] below [minContrast]. */
fun AirMood.textColorOnSurface(
    surface: Color,
    onSurface: Color,
    minContrast: Double = BODY_TEXT_CONTRAST,
): Color = step.textColorOnSurface(surface, onSurface, minContrast)

/** An [AirMood] colour used as text on the current theme surface. */
@Composable
fun AirMood.textColorOnSurface(minContrast: Double = BODY_TEXT_CONTRAST): Color =
    step.textColorOnSurface(minContrast)

/**
 * Contrast-safe colour for a signal bar in [SignalBars]. Filled and unfilled bars both maintain at
 * least [UI_COMPONENT_CONTRAST] (3 to 1) against their container in Light, Dark, and selected states.
 */
fun signalBarColor(filled: Boolean, onSelected: Boolean, scheme: ColorScheme): Color = when {
    onSelected && filled -> FloatingControl.OnSelected
    onSelected -> FloatingControl.OnSelected.copy(alpha = 0.72f).compositeOver(FloatingControl.Selected)
    filled -> scheme.secondary
    else -> scheme.outline
}

/**
 * Contrast-safe border colour for a [MoodStrip] block or [MoodLegend] swatch on [surface]. Ensures
 * light bands on light surfaces and dark bands on dark surfaces keep at least a 3 to 1 boundary.
 */
fun swatchBorderColor(scheme: ColorScheme): Color = scheme.outline

/** WCAG 2 relative luminance of an opaque sRGB colour, from 0 (black) to 1 (white). */
fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 2 contrast ratio between two opaque colours, from 1 to 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
