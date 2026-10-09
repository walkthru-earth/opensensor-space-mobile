package earth.walkthru.opensensor.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * Six moods, one per WHO step, as a face: eyes round or narrowing, mouth from smile to flat to
 * frown, a mask on the two worst steps, grey and neutral when there is no reading. Colour is never
 * the only carrier, the shape changes too. This is the data glyph, the peacock never replaces it.
 */
@Composable
internal fun MoodFace(mood: AirMood?, color: Color, modifier: Modifier = Modifier, description: String?) {
    val ink = onBand(color).copy(alpha = 0.78f)
    Canvas(if (description != null) modifier.semantics { contentDescription = description } else modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, r, c)
        drawCircle(Color.Black.copy(alpha = 0.08f), r, c, style = Stroke(r * 0.06f))
        val eyeY = c.y - r * 0.18f
        val eyeDx = r * 0.36f
        val eyeH = when (mood) {
            null, AirMood.FRESH, AirMood.FAIR -> r * 0.13f
            AirMood.HAZY, AirMood.POOR -> r * 0.09f
            AirMood.SMOKY, AirMood.THICK -> r * 0.05f
        }
        for (dx in listOf(-eyeDx, eyeDx)) {
            drawOval(ink, Offset(c.x + dx - r * 0.12f, eyeY - eyeH), Size(r * 0.24f, eyeH * 2))
        }
        val curve = when (mood) {
            AirMood.FRESH -> 1f
            AirMood.FAIR -> 0.6f
            AirMood.HAZY -> 0.2f
            AirMood.POOR -> -0.2f
            AirMood.SMOKY -> -0.6f
            AirMood.THICK -> -1f
            null -> 0f
        }
        val mouthY = c.y + r * 0.35f
        val half = r * 0.38f
        val path = Path().apply {
            moveTo(c.x - half, mouthY - curve * r * 0.12f)
            quadraticTo(c.x, mouthY + curve * r * 0.34f, c.x + half, mouthY - curve * r * 0.12f)
        }
        drawPath(path, ink, style = Stroke(width = r * 0.11f, cap = StrokeCap.Round))
        if (mood == AirMood.SMOKY || mood == AirMood.THICK) {
            drawRect(Color.White.copy(alpha = 0.85f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f))
            drawRect(Color.Black.copy(alpha = 0.25f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f), style = Stroke(r * 0.03f))
        }
    }
}
