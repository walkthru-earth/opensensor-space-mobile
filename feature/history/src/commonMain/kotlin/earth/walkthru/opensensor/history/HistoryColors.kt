package earth.walkthru.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
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
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.opensensor.history.resources.Res
import earth.walkthru.opensensor.history.resources.mood_fair_title
import earth.walkthru.opensensor.history.resources.mood_fresh_title
import earth.walkthru.opensensor.history.resources.mood_hazy_title
import earth.walkthru.opensensor.history.resources.mood_none
import earth.walkthru.opensensor.history.resources.mood_poor_title
import earth.walkthru.opensensor.history.resources.mood_smoky_title
import earth.walkthru.opensensor.history.resources.mood_thick_title
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
internal fun AirMood.titleText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> Res.string.mood_fresh_title
        AirMood.FAIR -> Res.string.mood_fair_title
        AirMood.HAZY -> Res.string.mood_hazy_title
        AirMood.POOR -> Res.string.mood_poor_title
        AirMood.SMOKY -> Res.string.mood_smoky_title
        AirMood.THICK -> Res.string.mood_thick_title
    },
)

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

/**
 * The mood face of the app (app ui/components/MoodFace.kt): eyes narrowing, mouth from smile to
 * frown, a mask on the two worst steps. Colour is never the only carrier, the shape changes too.
 */
@Composable
internal fun MoodFace(mood: AirMood?, color: Color, modifier: Modifier = Modifier) {
    val description = mood?.titleText() ?: stringResource(Res.string.mood_none)
    val ink = onBand(color).copy(alpha = 0.78f)
    Canvas(modifier.semantics { contentDescription = description }) {
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
