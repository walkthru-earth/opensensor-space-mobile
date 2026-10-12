package earth.walkthru.opensensor.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.opensensor.designsystem.moodWord
import earth.walkthru.opensensor.history.resources.Res
import earth.walkthru.opensensor.history.resources.who_step_above_it1
import earth.walkthru.opensensor.history.resources.who_step_guideline
import earth.walkthru.opensensor.history.resources.who_step_it1
import earth.walkthru.opensensor.history.resources.who_step_it2
import earth.walkthru.opensensor.history.resources.who_step_it3
import earth.walkthru.opensensor.history.resources.who_step_it4
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The History copy of the app's WHO colour rules (app ui/theme/Color.kt), so the shared screen paints
// a value exactly as Today does. The ARGB values live only in :core:airquality (WhoStep).

internal fun WhoStep.color(): Color = Color(color)

internal fun AirMood.color(): Color = step.color()

/** The step colour of [value] on this metric's scale, or [neutral] for a metric without one. */
internal fun MetricSpec.valueColor(value: Double, neutral: Color): Color = stepOf(value)?.color() ?: neutral

private val BandInkDark = Color(0xFF14181B)
private val BandInkLight = Color(0xFFFFFFFF)

/** Ink drawn on a band colour, whichever of dark or light contrasts more. */
internal fun onBand(band: Color): Color =
    if (contrastRatio(BandInkDark, band) >= contrastRatio(BandInkLight, band)) BandInkDark else BandInkLight

/** A band colour used as large text on the surface, or the normal text colour when it cannot reach 3:1. */
@Composable
internal fun AirMood.textColorOnSurface(): Color {
    val band = color()
    return if (contrastRatio(band, MaterialTheme.colorScheme.surface) >= LARGE_TEXT_CONTRAST) band else MaterialTheme.colorScheme.onSurface
}

internal const val LARGE_TEXT_CONTRAST = 3.0

/** WCAG 2 relative luminance of an opaque sRGB colour. */
internal fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 2 contrast ratio, 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

@Composable
internal fun AirMood.titleText(): String = moodWord(step)

@Composable
internal fun WhoStep.labelText(): String = stringResource(
    when (this) {
        WhoStep.GUIDELINE -> Res.string.who_step_guideline
        WhoStep.IT4 -> Res.string.who_step_it4
        WhoStep.IT3 -> Res.string.who_step_it3
        WhoStep.IT2 -> Res.string.who_step_it2
        WhoStep.IT1 -> Res.string.who_step_it1
        WhoStep.ABOVE_IT1 -> Res.string.who_step_above_it1
    },
)
