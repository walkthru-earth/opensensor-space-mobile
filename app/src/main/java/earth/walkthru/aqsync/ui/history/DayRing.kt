package earth.walkthru.aqsync.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.ui.device.MoodFace
import earth.walkthru.aqsync.ui.theme.color
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The day as a clock: one segment per elapsed hour (23 or 25 across DST), midnight at the top, each coloured by
 * its band, faded by its coverage, dashed when on estimated time, dark where nothing was recorded;
 * a tick for "now" when the day is today; the day's average and its mood in the middle. Reads like
 * a watch face — you see at once *when* the air was bad — and it is the shape the iOS widget can take.
 */
@Composable
fun DayRing(
    columns: List<HistoryColumn>,
    metric: MetricSpec,
    dayStartS: Long,
    dayEndS: Long,
    mean: Float?,
    coverage: Float,
    nowS: Long?,
    selected: HistoryColumn?,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val labelPaint = remember(muted, labelPx) { textPaint(muted, labelPx) }
    val mood = mean?.let { metric.moodOf(it.toDouble()) }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2 - labelPx * 1.4f
            val thickness = outer * 0.22f
            val r = outer - thickness / 2
            val daySeconds = (dayEndS - dayStartS).toFloat()
            fun angle(s: Long): Float = ((s - dayStartS) / daySeconds) * 360f - 90f // midnight at the top
            // Base track.
            drawCircle(empty, r, c, style = Stroke(thickness))
            for (col in columns) {
                val start = angle(col.startS)
                val sweep = ((col.endS - col.startS) / daySeconds) * 360f
                val m = col.mean
                if (m == null) continue
                val color = if (metric.banded) (metric.moodOf(m.toDouble()) ?: AirMood.FRESH).color() else primary
                val alpha = 0.3f + 0.7f * col.coverage
                val isSel = selected != null && selected.startS == col.startS
                drawArc(
                    color = color.copy(alpha = alpha),
                    startAngle = start,
                    sweepAngle = sweep - 0.6f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(if (isSel) thickness * 1.25f else thickness, pathEffect = if (col.estimated) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null),
                )
            }
            // Hour marks: 00 · 06 · 12 · 18 in the local zone.
            val day = Instant.ofEpochSecond(dayStartS).atZone(zone).toLocalDate()
            for (h in listOf(0, 6, 12, 18)) {
                val s = day.atTime(h, 0).atZone(zone).toEpochSecond()
                val a = angle(s) * PI.toFloat() / 180f
                val lr = outer + labelPx * 0.8f
                val text = "%02d".format(h)
                val w = labelPaint.measureText(text)
                drawContext.canvas.nativeCanvas.drawText(text, c.x + cos(a) * lr - w / 2, c.y + sin(a) * lr + labelPx * 0.35f, labelPaint)
                drawLine(muted.copy(alpha = 0.5f), Offset(c.x + cos(a) * (outer + 2f), c.y + sin(a) * (outer + 2f)), Offset(c.x + cos(a) * (outer + 8f), c.y + sin(a) * (outer + 8f)), 1.5f)
            }
            // Now marker.
            if (nowS != null && nowS >= dayStartS && nowS < dayEndS) {
                val a = angle(nowS) * PI.toFloat() / 180f
                drawLine(onSurface, Offset(c.x + cos(a) * (r - thickness * 0.8f), c.y + sin(a) * (r - thickness * 0.8f)), Offset(c.x + cos(a) * (r + thickness * 0.8f), c.y + sin(a) * (r + thickness * 0.8f)), 2.5f, cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(size * 0.5f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            if (mood != null) MoodFace(mood, mood.color(), Modifier.size(size * 0.16f))
            Text(
                mean?.let { if (it >= 100) it.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, it) } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = mood?.color() ?: onSurface,
            )
            Text(if (mean == null) "no readings" else metric.unit, style = MaterialTheme.typography.labelSmall, color = muted)
            if (mean != null) Text("${(coverage * 100).toInt()} % of the day", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
