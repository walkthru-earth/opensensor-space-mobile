package earth.walkthru.opensensor.today

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.opensensor.designsystem.FloatingControl
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Today's copy of the app's WHO colour rules (app ui/theme/Color.kt), the same as History's. The ARGB
// values live only in :core:airquality (WhoStep), so both apps paint a step the same colour.

internal fun WhoStep.color(): Color = Color(color)

internal fun AirMood.color(): Color = step.color()

private val BandInkDark = Color(0xFF14181B)
private val BandInkLight = Color(0xFFFFFFFF)

/** Ink drawn on a band colour, whichever of dark or light contrasts more. */
internal fun onBand(band: Color): Color =
    if (contrastRatio(BandInkDark, band) >= contrastRatio(BandInkLight, band)) BandInkDark else BandInkLight

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

/** The mood name on its own WHO step colour, with ink chosen for contrast. A small curved card. */
@Composable
internal fun MoodPill(mood: AirMood, modifier: Modifier = Modifier) {
    val band = mood.color()
    Surface(shape = FloatingControl.SmallShape, color = band, contentColor = onBand(band), modifier = modifier) {
        Text(
            mood.titleText(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
