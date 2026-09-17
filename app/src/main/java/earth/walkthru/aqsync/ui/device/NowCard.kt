package earth.walkthru.aqsync.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.theme.color

/**
 * The friendly reading: a character, a plain title, one line of advice, a comparison, then the
 * number — and always which window each statement is about ("right now" is a 10-second snapshot,
 * "today so far" a real average with its coverage). Colour is never the only carrier: every mood has
 * a title and the face changes shape.
 *
 * The face is a Canvas placeholder with the character's *behaviour* (five moods, band colour,
 * reduced-motion-safe because it does not move); the capybara artwork replaces [MoodFace] one-for-one
 * on both platforms.
 */
@Composable
fun NowCard(reading: NowReading, today: TodaySoFar?, fresh: Boolean, showNumber: Boolean, onSetTime: (() -> Unit)?) {
    val mood = (reading as? NowReading.Reading)?.mood
    val accent = mood?.color() ?: MaterialTheme.colorScheme.outline
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoodFace(mood, accent, Modifier.size(72.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (fresh) "right now" else "last reading")
                    when (reading) {
                        NowReading.Waiting -> Text("Waiting for the first reading…", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is NowReading.SensorNotReady -> {
                            Text(reading.status.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall)
                            Text(reading.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is NowReading.Reading -> {
                            Text(reading.mood.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = if (fresh) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(reading.mood.comparison, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (reading is NowReading.Reading) {
                Spacer(Modifier.height(12.dp))
                Text(reading.mood.advice, style = MaterialTheme.typography.bodyLarge)
                if (showNumber) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "PM2.5 ${fmtPm(reading.pm25)} µg/m³ — a 10-second snapshot, not a daily average",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Eyebrow("today so far")
            if (today != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoodFace(today.mood, today.mood.color(), Modifier.size(22.dp))
                    Text(
                        "${today.mood.title} · average ${fmtPm(today.pm25Mean.toDouble())} µg/m³ over ${(today.coverage * 100).toInt()} % of the day (${today.validRows} readings since midnight)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    if (onSetTime != null) "No timed readings on this phone yet today. Give the logger the time, then sync, and the day's average appears here." else "No timed readings on this phone yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmtPm(v: Double): String = if (v >= 100) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v).removeSuffix(".0")

/** Five moods as a face: eyes level or drooping, mouth from smile to flat to frown; grey and neutral when there is no reading. */
@Composable
fun MoodFace(mood: AirMood?, color: Color, modifier: Modifier = Modifier) {
    val description = mood?.title ?: "no reading"
    Canvas(modifier.semantics { contentDescription = description }) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, r, c)
        drawCircle(Color.Black.copy(alpha = 0.08f), r, c, style = Stroke(r * 0.06f))
        val ink = Color.Black.copy(alpha = 0.72f)
        // Eyes: happy moods keep them round; worse moods narrow them.
        val eyeY = c.y - r * 0.18f
        val eyeDx = r * 0.36f
        val eyeH = when (mood) {
            null, AirMood.FRESH, AirMood.FINE -> r * 0.13f
            AirMood.HAZY -> r * 0.09f
            AirMood.SMOKY, AirMood.THICK -> r * 0.05f
        }
        for (dx in listOf(-eyeDx, eyeDx)) {
            drawOval(ink, Offset(c.x + dx - r * 0.12f, eyeY - eyeH), Size(r * 0.24f, eyeH * 2))
        }
        // Mouth curvature: +1 smile … −1 frown.
        val curve = when (mood) {
            AirMood.FRESH -> 1f
            AirMood.FINE -> 0.5f
            AirMood.HAZY -> 0f
            AirMood.SMOKY -> -0.5f
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
        // Mask for the smoky moods: a band across the mouth.
        if (mood == AirMood.SMOKY || mood == AirMood.THICK) {
            drawRect(Color.White.copy(alpha = 0.85f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f))
            drawRect(ink.copy(alpha = 0.25f), Offset(c.x - half * 1.1f, mouthY - r * 0.2f), Size(half * 2.2f, r * 0.42f), style = Stroke(r * 0.03f))
        }
    }
}
