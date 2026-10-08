package earth.walkthru.aqsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.ui.theme.onBand

/**
 * Six moods, one per WHO step, as a face: eyes round or narrowing, mouth from smile to flat to
 * frown, a mask on the two worst steps, grey and neutral when there is no reading. Colour is never
 * the only carrier, the shape changes too. The features use the ink that contrasts with the step
 * colour (`onBand`), so the two darkest steps get light features.
 *
 * This is the data glyph. The peacock mascot is the friendly guide and never replaces it.
 */
@Composable
fun MoodFace(mood: AirMood?, color: Color, modifier: Modifier = Modifier, description: String? = mood?.titleText() ?: stringResource(R.string.mood_none)) {
    val ink = onBand(color).copy(alpha = 0.78f)
    Canvas(if (description != null) modifier.semantics { contentDescription = description } else modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, r, c)
        drawCircle(Color.Black.copy(alpha = 0.08f), r, c, style = Stroke(r * 0.06f))
        // Eyes: happy moods keep them round, worse moods narrow them.
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
        // Mouth curvature, +1 smile to -1 frown.
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
        // A mask for the smoky moods, a band across the mouth.
        if (mood == AirMood.SMOKY || mood == AirMood.THICK) {
            drawRect(Color.White.copy(alpha = 0.85f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f))
            drawRect(Color.Black.copy(alpha = 0.25f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f), style = Stroke(r * 0.03f))
        }
    }
}
