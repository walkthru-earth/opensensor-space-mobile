package earth.walkthru.aqsync.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.data.history.HistoryPoint
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.ui.theme.BandElevated
import earth.walkthru.aqsync.ui.theme.BandGood
import earth.walkthru.aqsync.ui.theme.BandHigh
import earth.walkthru.aqsync.ui.theme.BandModerate
import earth.walkthru.aqsync.ui.theme.BandVeryHigh
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

/**
 * PM2.5 display bands, shared with the Now card's character. Upper bounds in µg/m³; the WHO 2021
 * 24-hour guideline is 15. These colour a chart and a mood, they are not an AQI.
 */
val PM25_BANDS: List<Pair<Float, Color>> = listOf(15f to BandGood, 35f to BandModerate, 55f to BandElevated, 150f to BandHigh, Float.POSITIVE_INFINITY to BandVeryHigh)

/**
 * The History chart, drawn with `Canvas` so the same spec ports to Swift Charts: band shading behind
 * the series (PM2.5 only), a min/max envelope plus mean line for aggregated ranges, a line for raw
 * rows, gaps where a bucket has no valid rows, and a coverage strip underneath. Nothing above a few
 * thousand points ever reaches this function (the repository bins first).
 */
@Composable
fun HistoryChart(series: HistorySeries, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary
    val envelope = line.copy(alpha = 0.22f)
    val grid = muted.copy(alpha = 0.25f)
    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }
    val labelPaint = remember(muted, labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb((muted.alpha * 255).toInt(), (muted.red * 255).toInt(), (muted.green * 255).toInt(), (muted.blue * 255).toInt())
            textSize = labelPx
            isAntiAlias = true
        }
    }
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val leftPad = labelPx * 3.2f
        val bottomPad = labelPx * 3.4f
        val coverageH = labelPx * 0.7f
        val plot = Rect(leftPad, labelPx * 0.6f, size.width, size.height - bottomPad)
        val yMax = yAxisMax(series)
        fun x(t: Long) = plot.left + (t - series.fromS).toFloat() / (series.toS - series.fromS) * plot.width
        fun y(v: Float) = plot.bottom - (v / yMax).coerceIn(0f, 1f) * plot.height

        // Band shading (PM2.5 only).
        if (series.metric.banded) {
            var lower = 0f
            for ((upper, color) in PM25_BANDS) {
                val top = y(minOf(upper, yMax))
                val bottom = y(lower)
                if (bottom > top) drawRect(color.copy(alpha = 0.10f), Offset(plot.left, top), Size(plot.width, bottom - top))
                lower = upper
                if (upper >= yMax) break
            }
        }
        // Horizontal grid + y labels.
        val yStep = niceStep(yMax)
        var v = 0f
        while (v <= yMax + 1e-3f) {
            val yy = y(v)
            drawLine(grid, Offset(plot.left, yy), Offset(plot.right, yy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(trim(v), 0f, yy + labelPx * 0.35f, labelPaint)
            v += yStep
        }
        // X ticks.
        for ((t, label) in xTicks(series, zone)) {
            val xx = x(t)
            drawLine(grid, Offset(xx, plot.top), Offset(xx, plot.bottom), strokeWidth = 1f)
            val w = labelPaint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(label, (xx - w / 2).coerceIn(plot.left, plot.right - w), plot.bottom + labelPx * 1.3f, labelPaint)
        }

        // Envelope (aggregated ranges).
        drawEnvelope(series.points, ::x, ::y, envelope)
        // Mean line, broken at gaps; estimated-time stretches dashed.
        val stroke = if (series.range == HistoryRange.YEAR) 2.5f else 2f
        drawMeanLine(series.points.map { if (it.estimated) it.copy(mean = null) else it }, ::x, ::y, line, strokeWidth = stroke)
        drawMeanLine(series.points.map { if (!it.estimated) it.copy(mean = null) else it }, ::x, ::y, line, strokeWidth = stroke, dashed = true)

        // Coverage strip: per point, alpha = coverage; empty where no rows at all.
        val stripTop = plot.bottom + labelPx * 1.7f
        for (p in series.points) {
            val x0 = x(p.startS)
            val x1 = max(x(p.endS), x0 + 1f)
            drawRect(onSurface.copy(alpha = 0.12f + 0.55f * p.coverage), Offset(x0, stripTop), Size(x1 - x0, coverageH))
        }
        // Axis line.
        drawLine(muted, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1f)
    }
}

private fun DrawScope.drawEnvelope(points: List<HistoryPoint>, x: (Long) -> Float, y: (Float) -> Float, color: Color) {
    var run = ArrayList<HistoryPoint>()
    fun flush() {
        if (run.size >= 2) {
            val path = Path()
            run.forEachIndexed { i, p -> val xx = (x(p.startS) + x(p.endS)) / 2; if (i == 0) path.moveTo(xx, y(p.max!!)) else path.lineTo(xx, y(p.max!!)) }
            run.asReversed().forEach { p -> path.lineTo((x(p.startS) + x(p.endS)) / 2, y(p.min!!)) }
            path.close()
            drawPath(path, color)
        }
        run = ArrayList()
    }
    for (p in points) {
        if (p.min != null && p.max != null && p.mean != null) run += p else flush()
    }
    flush()
}

private fun DrawScope.drawMeanLine(points: List<HistoryPoint>, x: (Long) -> Float, y: (Float) -> Float, color: Color, strokeWidth: Float, dashed: Boolean = false) {
    val path = Path()
    var open = false
    var lastEnd = Long.MIN_VALUE
    var singles = ArrayList<Offset>()
    var runLength = 0
    var lastPoint = Offset.Zero
    for (p in points) {
        val m = p.mean
        // A gap: no valid rows, or a hole in time larger than one point's width (raw rows: > 60 s).
        val hole = lastEnd != Long.MIN_VALUE && p.startS - lastEnd > (p.endS - p.startS) * 2
        if (m == null || hole) {
            if (runLength == 1) singles += lastPoint
            open = false
            runLength = 0
            if (m == null) { lastEnd = p.endS; continue }
        }
        val xx = (x(p.startS) + x(p.endS)) / 2
        val yy = y(m!!)
        if (!open) { path.moveTo(xx, yy); open = true } else path.lineTo(xx, yy)
        lastPoint = Offset(xx, yy)
        runLength++
        lastEnd = p.endS
    }
    if (runLength == 1) singles += lastPoint
    drawPath(path, color, style = Stroke(width = strokeWidth, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null))
    // Isolated points would be invisible as a zero-length path: draw them as dots.
    for (s in singles) drawCircle(color, radius = strokeWidth * 1.2f, center = s)
}

private fun yAxisMax(series: HistorySeries): Float {
    val top = series.points.maxOfOrNull { max(it.max ?: 0f, it.mean ?: 0f) } ?: 0f
    val floor = if (series.metric.banded) 40f else 10f
    val raw = max(top * 1.15f, floor)
    val step = niceStep(raw)
    return ceil(raw / step) * step
}

private fun niceStep(range: Float): Float {
    val rough = range / 4f
    val mag = Math.pow(10.0, Math.floor(Math.log10(rough.toDouble()))).toFloat()
    val n = rough / mag
    return mag * when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 5f -> 5f
        else -> 10f
    }
}

private fun trim(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

private fun xTicks(series: HistorySeries, zone: ZoneId): List<Pair<Long, String>> {
    val fmt = when (series.range) {
        HistoryRange.DAY -> DateTimeFormatter.ofPattern("HH:mm")
        HistoryRange.WEEK -> DateTimeFormatter.ofPattern("EEE")
        HistoryRange.MONTH -> DateTimeFormatter.ofPattern("d MMM")
        HistoryRange.YEAR -> DateTimeFormatter.ofPattern("MMM")
    }.withZone(zone)
    val step = when (series.range) {
        HistoryRange.DAY -> 6 * 3600L
        HistoryRange.WEEK -> 86_400L
        HistoryRange.MONTH -> 7 * 86_400L
        HistoryRange.YEAR -> 61 * 86_400L
    }
    val offset = zone.rules.getOffset(Instant.ofEpochSecond(series.toS)).totalSeconds
    // Align ticks to local midnight / hour boundaries.
    var t = Math.floorDiv(series.fromS + offset, step) * step - offset
    val out = ArrayList<Pair<Long, String>>()
    while (t <= series.toS) {
        if (t >= series.fromS) out += t to fmt.format(Instant.ofEpochSecond(t))
        t += step
    }
    return out
}
