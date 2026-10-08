package earth.walkthru.aqsync.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.ChartAxes
import earth.walkthru.aqsync.data.history.ChartAxis
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.aqsync.ui.theme.LARGE_TEXT_CONTRAST
import earth.walkthru.aqsync.ui.theme.contrastRatio
import earth.walkthru.aqsync.ui.theme.valueColor
import earth.walkthru.aqsync.units.UnitConversion
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.displayAxisTicks
import earth.walkthru.aqsync.units.formatFixed
import earth.walkthru.aqsync.units.tickDecimals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mean columns (hourly, daily or weekly, see [averagingNote]) on a neutral background, each bar
 * coloured by its own WHO 2021 step, the value *is* the colour, so a week reads at a glance. This
 * follows the qualitative display advice of Mazama Science and the accessible AQI scale of South
 * Coast AQMD. Bars, not shaded background bands. The y axis starts at zero and, for PM2.5 and PM10,
 * its top snaps up to a WHO edge ([ChartAxes.of]) so the dashed, labelled 24-hour guideline line is
 * always in view and two days compare on the same scale. A peak whisker sits inside each slot,
 * coverage is opacity (a slot built from three readings is faint), estimated-time slots are hollow
 * with a dashed outline, and the axis follows the 95th percentile instead of the tallest spike:
 * outliers are clipped to a small cap that states their value. Neutral metrics are drawn in a
 * neutral ink, Signal blue is kept for selection. Touch or drag to inspect a slot; the selection is
 * reported to the caller, which names the step in words. Pure `Canvas`, so the same spec is a
 * Swift Charts `BarMark` on iOS.
 */
@Composable
fun BandColumnChart(
    columns: List<HistoryColumn>,
    metric: MetricSpec,
    range: HistoryRange,
    fromS: Long,
    toS: Long,
    selected: HistoryColumn?,
    onSelect: (HistoryColumn?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    zone: ZoneId = ZoneId.systemDefault(),
    /** X-axis labels `(position, text)` in the chart's own units; null = time ticks for [range]. The "chart by file" strip passes file indices. */
    xLabels: List<Pair<Long, String>>? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val gridColor = muted.copy(alpha = 0.18f)
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val labelPaint = remember(muted, labelPx) { textPaint(muted, labelPx) }
    val whoLabel = stringResource(R.string.chart_who_24h)
    val capPaint = remember(onSurface, labelPx) { textPaint(onSurface, labelPx * 0.95f, bold = true) }
    val guidePaint = remember(onSurface, labelPx) { textPaint(onSurface, labelPx) }

    // Banded metrics snap up to a WHO edge (PM2.5 at least 37.5, PM10 at least 75); spikes beyond are capped.
    val axis = remember(columns, metric) { ChartAxes.of(columns, metric) }
    val yMax = axis.max
    val guideline = ChartAxes.guidelineOf(metric)?.takeIf { it <= yMax }
    // Geometry, data and bands stay on the SI values. Ticks are chosen round in the unit shown.
    val units = LocalUnits.current
    val yTicks = remember(axis, metric.unit, units.system) { yAxisTicks(axis, metric.unit, units.system) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(columns, fromS, toS) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(x: Float): HistoryColumn? {
                        val left = labelPx * 3.2f
                        val width = size.width - left
                        if (width <= 0f) return null
                        val t = fromS + ((x - left) / width * (toS - fromS)).toLong()
                        return columns.firstOrNull { t >= it.startS && t < it.endS }
                    }
                    onSelect(pick(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        onSelect(pick(change.position.x))
                        change.consume()
                    }
                }
            },
    ) {
        val left = labelPx * 3.2f
        val bottom = size.height - labelPx * 2.2f
        val top = labelPx * 1.4f
        val plot = Rect(left, top, size.width, bottom)
        fun x(t: Long) = plot.left + (t - fromS).toFloat() / (toS - fromS) * plot.width
        fun y(v: Float) = plot.bottom - axis.fraction(v) * plot.height
        val zeroY = y(0f)

        // Grid + y labels, at round values in the unit shown. The tick sits at its SI position.
        for ((at, label) in yTicks) {
            val yy = y(at)
            drawLine(gridColor, Offset(plot.left, yy), Offset(plot.right, yy), 1f)
            drawContext.canvas.nativeCanvas.drawText(label, 0f, yy + labelPx * 0.35f, labelPaint)
        }

        // Off-scale columns: label only the tallest of each contiguous run, or the labels pile up.
        val labelCap = BooleanArray(columns.size)
        run {
            var i = 0
            while (i < columns.size) {
                fun peak(k: Int) = columns[k].max?.takeIf(Float::isFinite) ?: columns[k].mean?.takeIf(Float::isFinite) ?: 0f
                fun over(k: Int) = peak(k) > yMax
                if (!over(i)) { i++; continue }
                var j = i
                var best = i
                while (j < columns.size && over(j)) {
                    if (peak(j) > peak(best)) best = j
                    j++
                }
                labelCap[best] = true
                i = j
            }
        }
        // Columns.
        val slotW = if (columns.isNotEmpty()) x(columns[0].endS) - x(columns[0].startS) else 0f
        val gap = (slotW * 0.18f).coerceIn(1f, 6f)
        val radius = CornerRadius((slotW * 0.3f).coerceAtMost(8f))
        for ((ci, c) in columns.withIndex()) {
            val m = c.mean?.takeIf(Float::isFinite) ?: continue
            val x0 = x(c.startS) + gap / 2
            val w = max(slotW - gap, 1.5f)
            // "Clipped" when the mean or the peak inside the slot is off-scale: the cap says how high it really went.
            val peak = c.max?.takeIf(Float::isFinite)
            val clipped = (peak ?: m) > yMax
            val meanY = y(m)
            val topY = minOf(meanY, zeroY)
            val barHeight = abs(meanY - zeroY)
            val color = metric.valueColor(m.toDouble(), muted)
            val alpha = 0.35f + 0.65f * c.coverage
            val isSel = selected != null && selected.startS == c.startS
            if (c.estimated) {
                drawRoundRect(color.copy(alpha = alpha), Offset(x0, topY), Size(w, barHeight), radius, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
                drawRoundRect(color.copy(alpha = alpha * 0.25f), Offset(x0, topY), Size(w, barHeight), radius)
            } else {
                drawRoundRect(color.copy(alpha = alpha), Offset(x0, topY), Size(w, barHeight), radius)
                // The lightest steps are faint on light paper, a hairline keeps the bar's edge.
                if (contrastRatio(color, surface) < LARGE_TEXT_CONTRAST) {
                    drawRoundRect(onSurface.copy(alpha = 0.22f), Offset(x0, topY), Size(w, barHeight), radius, style = Stroke(1f))
                }
            }
            // Peak whisker inside the slot (only when it adds information).
            if (peak != null && peak - m > abs(m) * 0.15f) {
                val py = y(minOf(peak, yMax))
                drawLine(color.copy(alpha = 0.7f), Offset(x0 + w / 2, meanY), Offset(x0 + w / 2, py), 1.5f)
            }
            if (clipped) {
                // Off-scale: a cap with the value, so the spike is neither hidden nor allowed to flatten the day.
                val cap = Path().apply {
                    moveTo(x0 + w / 2, plot.top - labelPx * 0.2f)
                    lineTo(x0 + w / 2 - 5f, plot.top + 6f)
                    lineTo(x0 + w / 2 + 5f, plot.top + 6f)
                    close()
                }
                drawPath(cap, color)
                if (labelCap[ci]) {
                    val text = fmtAxis(units.value(peak ?: m, metric.unit))
                    val tw = capPaint.measureText(text)
                    drawContext.canvas.nativeCanvas.drawText(text, (x0 + w / 2 - tw / 2).coerceIn(plot.left, plot.right - tw), plot.top - labelPx * 0.45f, capPaint)
                }
            }
            if (isSel) {
                drawRoundRect(onSurface, Offset(x0 - 1f, topY - 1f), Size(w + 2f, barHeight + 2f), radius, style = Stroke(2f))
            }
        }
        // Gaps: a faint dotted baseline where nothing was recorded.
        for (c in columns) {
            if (c.mean == null) {
                drawLine(muted.copy(alpha = 0.35f), Offset(x(c.startS) + gap / 2, zeroY - 1f), Offset(x(c.endS) - gap / 2, zeroY - 1f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)))
            }
        }
        // WHO 24-hour guideline (PM2.5 and PM10), dashed neutral ink above the bars, labelled at the right.
        guideline?.let { g ->
            val yy = y(g)
            drawLine(onSurface.copy(alpha = 0.75f), Offset(plot.left, yy), Offset(plot.right, yy), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            val w = guidePaint.measureText(whoLabel)
            drawContext.canvas.nativeCanvas.drawText(whoLabel, plot.right - w, yy - labelPx * 0.4f, guidePaint)
        }
        // Cursor.
        selected?.let { s ->
            val cx = (x(s.startS) + x(s.endS)) / 2
            drawLine(onSurface.copy(alpha = 0.6f), Offset(cx, plot.top), Offset(cx, plot.bottom), 1f)
        }
        // X ticks.
        for ((t, label) in xLabels ?: ticks(range, fromS, toS, zone)) {
            val xx = x(t)
            drawLine(gridColor, Offset(xx, plot.bottom), Offset(xx, plot.bottom + 4f), 1f)
            val w = labelPaint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(label, (xx - w / 2).coerceIn(plot.left, plot.right - w), plot.bottom + labelPx * 1.5f, labelPaint)
        }
        drawLine(muted.copy(alpha = 0.6f), Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1f)
        if (axis.min < 0f) drawLine(muted.copy(alpha = 0.6f), Offset(plot.left, zeroY), Offset(plot.right, zeroY), 1f)
    }
}

/**
 * What each column of [range] averages, and whether the WHO 24-hour guideline line is drawn for
 * [metric]. Stated under the chart, because a 1-hour mean is read against a 24-hour value.
 */
@Composable
fun averagingNote(range: HistoryRange, metric: MetricSpec): String {
    val mean = stringResource(
        when (range) {
            HistoryRange.DAY, HistoryRange.WEEK -> R.string.chart_mean_hourly
            HistoryRange.MONTH -> R.string.chart_mean_daily
            HistoryRange.YEAR -> R.string.chart_mean_weekly
        },
    )
    return if (ChartAxes.guidelineOf(metric) != null) mean + " " + stringResource(R.string.chart_guideline_note) else mean
}

internal fun textPaint(color: Color, sizePx: Float, bold: Boolean = false) = android.graphics.Paint().apply {
    this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    textSize = sizePx
    isAntiAlias = true
    isFakeBoldText = bold
}

/**
 * Y-axis gridlines as `(SI position, label)`. A unit shown as is keeps the SI ticks from
 * [ChartAxes] (min to max by step). A converted unit gets round ticks in the shown unit at the same
 * density, each mapped back to SI with [UnitConversion.toSource], so 0 to 40 °C reads 40, 60, 80, 100 °F.
 */
internal fun yAxisTicks(axis: ChartAxis, unit: String, system: UnitSystem): List<Pair<Float, String>> {
    if (!UnitConversion.converts(unit, system)) {
        val out = ArrayList<Pair<Float, String>>()
        var v = axis.min
        while (v <= axis.max + 1e-3f) {
            out += v to fmtAxis(v)
            v += axis.step
        }
        return out
    }
    val target = if (axis.step > 0f) (axis.span / axis.step).roundToInt() + 1 else 5
    val ticks = displayAxisTicks(axis.min.toDouble(), axis.max.toDouble(), unit, system, target.coerceIn(3, 9))
    val decimals = if (ticks.size >= 2) tickDecimals(ticks[1].shown - ticks[0].shown) else UnitConversion.displayDecimals(unit, system) ?: 0
    return ticks.map { it.source.toFloat() to formatFixed(it.shown, decimals) }
}

internal fun fmtAxis(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

internal fun ticks(range: HistoryRange, fromS: Long, toS: Long, zone: ZoneId): List<Pair<Long, String>> {
    val fmt = when (range) {
        HistoryRange.DAY -> DateTimeFormatter.ofPattern("HH:mm")
        HistoryRange.WEEK -> DateTimeFormatter.ofPattern("EEE")
        HistoryRange.MONTH -> DateTimeFormatter.ofPattern("d MMM")
        HistoryRange.YEAR -> DateTimeFormatter.ofPattern("MMM")
    }.withZone(zone)
    val out = ArrayList<Pair<Long, String>>()
    val first = Instant.ofEpochSecond(fromS).atZone(zone).toLocalDate()
    if (range == HistoryRange.DAY) {
        for (hour in 0..24 step 6) {
            val t = if (hour == 24) {
                first.plusDays(1).atStartOfDay(zone).toEpochSecond()
            } else {
                first.atTime(hour, 0).atZone(zone).toEpochSecond()
            }
            if (t in fromS..toS) out += t to fmt.format(Instant.ofEpochSecond(t))
        }
    } else {
        val stepDays = when (range) {
            HistoryRange.WEEK -> 1L
            HistoryRange.MONTH -> 7L
            HistoryRange.YEAR -> 61L
            HistoryRange.DAY -> error("handled above")
        }
        var day = first
        while (true) {
            val t = day.atStartOfDay(zone).toEpochSecond()
            if (t > toS) break
            if (t >= fromS) out += t to fmt.format(Instant.ofEpochSecond(t))
            day = day.plusDays(stepDays)
        }
    }
    return out
}
