package earth.walkthru.opensensor.history

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.data.history.ChartAxes
import earth.walkthru.aqsync.data.history.ChartAxis
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.data.history.startOfDay
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitConversion
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.displayAxisTicks
import earth.walkthru.aqsync.units.formatFixed
import earth.walkthru.aqsync.units.tickDecimals
import earth.walkthru.opensensor.designsystem.listSeparator
import earth.walkthru.opensensor.designsystem.spokenList
import earth.walkthru.opensensor.history.resources.Res
import earth.walkthru.opensensor.history.resources.chart_bars_title
import earth.walkthru.opensensor.history.resources.chart_guideline_24h
import earth.walkthru.opensensor.history.resources.chart_guideline_note
import earth.walkthru.opensensor.history.resources.chart_guideline_note_simple
import earth.walkthru.opensensor.history.resources.chart_highest
import earth.walkthru.opensensor.history.resources.chart_highest_at
import earth.walkthru.opensensor.history.resources.chart_mean_daily
import earth.walkthru.opensensor.history.resources.chart_mean_hourly
import earth.walkthru.opensensor.history.resources.chart_mean_weekly
import earth.walkthru.opensensor.history.resources.chart_who_24h
import earth.walkthru.opensensor.history.resources.history_no_readings_short
import earth.walkthru.opensensor.history.resources.history_week_of
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mean columns (hourly, daily or weekly, see [averagingNote]) on a neutral background, each bar
 * coloured by its own WHO 2021 step, so a week reads at a glance. The y axis starts at zero and, for
 * PM2.5 and PM10, its top snaps up to a WHO edge ([ChartAxes.of]) so the dashed, labelled 24-hour
 * guideline line is always in view. A peak whisker sits inside each slot, coverage is opacity,
 * estimated-time slots are hollow with a dashed outline, and the axis follows the 95th percentile:
 * outliers are clipped to a small cap that states their value. Touch or drag to inspect a slot.
 *
 * Pure common Compose (a [TextMeasurer], never a platform canvas), the same chart on Android and iOS.
 * Time runs left to right in every language.
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
    zone: TimeZone = TimeZone.currentSystemDefault(),
    units: UnitFormatter = UnitFormatter.Metric,
    /** X-axis labels `(position, text)` in the chart's own units, null for time ticks. The "chart by file" strip passes file indices. */
    xLabels: List<Pair<Long, String>>? = null,
    /** Simple labels the guideline line in plain words and never names WHO. */
    level: DetailLevel = DetailLevel.DETAILED,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val gridColor = muted.copy(alpha = 0.18f)
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, 11) }
    val capStyle = remember(onSurface) { chartTextStyle(onSurface, 10, bold = true) }
    val guideStyle = remember(onSurface) { chartTextStyle(onSurface, 11) }
    val whoLabel = stringResource(guidelineLabelRes(level))

    val axis = remember(columns, metric) { ChartAxes.of(columns, metric) }
    val yMax = axis.max
    val guideline = ChartAxes.guidelineOf(metric)?.takeIf { it <= yMax }
    // Geometry, data and bands stay on the SI values. Ticks are chosen round in the unit shown.
    val yTicks = remember(axis, metric.unit, units.system) { yAxisTicks(axis, metric.unit, units.system) }
    val xTicks = remember(xLabels, range, fromS, toS, zone) { xLabels ?: timeTicks(range, fromS, toS, zone) }
    // The spoken chart: what it shows and its highest bar with the WHO step, built from the same data.
    // A file strip (xLabels) has file indices, not times, so its highest bar names no slot.
    val slot: (@Composable (HistoryColumn) -> String)? = if (xLabels == null) {
        { c -> slotLabel(c, range, zone) }
    } else {
        null
    }
    val summary = chartSummary(stringResource(Res.string.chart_bars_title, metric.label), columns, metric, units, stepNamed = level.detailed, slot = slot)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = summary }
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

        for ((at, label) in yTicks) {
            val yy = y(at)
            drawLine(gridColor, Offset(plot.left, yy), Offset(plot.right, yy), 1f)
            drawLabel(measurer.measure(label, labelStyle), 0f, yy + labelPx * 0.35f)
        }

        // Off-scale columns: label only the tallest of each contiguous run, or the labels pile up.
        val labelCap = BooleanArray(columns.size)
        run {
            fun peak(k: Int) = columns[k].max?.takeIf(Float::isFinite) ?: columns[k].mean?.takeIf(Float::isFinite) ?: 0f
            fun over(k: Int) = peak(k) > yMax
            var i = 0
            while (i < columns.size) {
                if (!over(i)) {
                    i++
                    continue
                }
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
        val slotW = if (columns.isNotEmpty()) x(columns[0].endS) - x(columns[0].startS) else 0f
        val gap = (slotW * 0.18f).coerceIn(1f, 6f)
        val radius = CornerRadius((slotW * 0.3f).coerceAtMost(8f))
        for ((ci, c) in columns.withIndex()) {
            val m = c.mean?.takeIf(Float::isFinite) ?: continue
            val x0 = x(c.startS) + gap / 2
            val w = max(slotW - gap, 1.5f)
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
            if (peak != null && peak - m > abs(m) * 0.15f) {
                val py = y(minOf(peak, yMax))
                drawLine(color.copy(alpha = 0.7f), Offset(x0 + w / 2, meanY), Offset(x0 + w / 2, py), 1.5f)
            }
            if (clipped) {
                val cap = Path().apply {
                    moveTo(x0 + w / 2, plot.top - labelPx * 0.2f)
                    lineTo(x0 + w / 2 - 5f, plot.top + 6f)
                    lineTo(x0 + w / 2 + 5f, plot.top + 6f)
                    close()
                }
                drawPath(cap, color)
                if (labelCap[ci]) {
                    val layout = measurer.measure(fmtAxis(units.value(peak ?: m, metric.unit)), capStyle)
                    val tw = layout.size.width.toFloat()
                    drawLabel(layout, (x0 + w / 2 - tw / 2).coerceIn(plot.left, max(plot.left, plot.right - tw)), plot.top - labelPx * 0.45f)
                }
            }
            if (isSel) {
                drawRoundRect(onSurface, Offset(x0 - 1f, topY - 1f), Size(w + 2f, barHeight + 2f), radius, style = Stroke(2f))
            }
        }
        for (c in columns) {
            if (c.mean == null) {
                drawLine(muted.copy(alpha = 0.35f), Offset(x(c.startS) + gap / 2, zeroY - 1f), Offset(x(c.endS) - gap / 2, zeroY - 1f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)))
            }
        }
        guideline?.let { g ->
            val yy = y(g)
            drawLine(onSurface.copy(alpha = 0.75f), Offset(plot.left, yy), Offset(plot.right, yy), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            val layout = measurer.measure(whoLabel, guideStyle)
            drawLabel(layout, plot.right - layout.size.width, yy - labelPx * 0.4f)
        }
        selected?.let { s ->
            val cx = (x(s.startS) + x(s.endS)) / 2
            drawLine(onSurface.copy(alpha = 0.6f), Offset(cx, plot.top), Offset(cx, plot.bottom), 1f)
        }
        for ((t, label) in xTicks) {
            val xx = x(t)
            drawLine(gridColor, Offset(xx, plot.bottom), Offset(xx, plot.bottom + 4f), 1f)
            val layout = measurer.measure(label, labelStyle)
            val w = layout.size.width.toFloat()
            drawLabel(layout, (xx - w / 2).coerceIn(plot.left, max(plot.left, plot.right - w)), plot.bottom + labelPx * 1.5f)
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
fun averagingNote(range: HistoryRange, metric: MetricSpec, level: DetailLevel = DetailLevel.DETAILED): String {
    val mean = stringResource(
        when (range) {
            HistoryRange.DAY, HistoryRange.WEEK -> Res.string.chart_mean_hourly
            HistoryRange.MONTH -> Res.string.chart_mean_daily
            HistoryRange.YEAR -> Res.string.chart_mean_weekly
        },
    )
    return if (ChartAxes.guidelineOf(metric) != null) mean + " " + stringResource(guidelineNoteRes(level)) else mean
}

/**
 * A screen reader summary of a chart: [title], then [chartHighest], or "no readings". Built from the
 * drawn columns, so it never says more than the picture.
 */
@Composable
internal fun chartSummary(
    title: String,
    columns: List<HistoryColumn>,
    metric: MetricSpec,
    units: UnitFormatter,
    stepNamed: Boolean = true,
    slot: (@Composable (HistoryColumn) -> String)?,
): String = title + ", " + (chartHighest(columns, metric, units, stepNamed, slot) ?: stringResource(Res.string.history_no_readings_short))

/** The label on the guideline line. Simple never names WHO. */
internal fun guidelineLabelRes(level: DetailLevel): StringResource = if (level.detailed) Res.string.chart_who_24h else Res.string.chart_guideline_24h

/** The note under the chart about the dashed line. Simple never names WHO. */
internal fun guidelineNoteRes(level: DetailLevel): StringResource = if (level.detailed) Res.string.chart_guideline_note else Res.string.chart_guideline_note_simple

/** The highest mean of [columns] with the time [slot] names (when given) and its WHO step, null without readings. */
@Composable
internal fun chartHighest(
    columns: List<HistoryColumn>,
    metric: MetricSpec,
    units: UnitFormatter,
    /** False leaves out the WHO step, for Simple. */
    stepNamed: Boolean = true,
    slot: (@Composable (HistoryColumn) -> String)?,
): String? {
    val top = columns.filter { it.mean?.isFinite() == true }.maxByOrNull { it.mean ?: Float.NEGATIVE_INFINITY }
    val mean = top?.mean ?: return null
    val value = fmtValue(units.value(mean, metric.unit))
    val unit = units.unit(metric.unit)
    val highest = if (slot != null) stringResource(Res.string.chart_highest_at, value, unit, slot(top)) else stringResource(Res.string.chart_highest, value, unit)
    val step = if (stepNamed) metric.stepOf(mean.toDouble())?.labelText() else null
    return spokenList(listOfNotNull(highest, step), listSeparator())
}

/** A column's time in words for a screen reader: the hour for a day, weekday and hour for a week, the date for a month, the week for a year. */
@Composable
internal fun slotLabel(c: HistoryColumn, range: HistoryRange, zone: TimeZone): String = when (range) {
    HistoryRange.DAY -> formatEpoch(c.startS, zone, "HH:mm")
    HistoryRange.WEEK -> formatEpoch(c.startS, zone, "EEEE HH:mm")
    HistoryRange.MONTH -> formatEpoch(c.startS, zone, "EEEE d MMMM")
    HistoryRange.YEAR -> stringResource(Res.string.history_week_of, formatEpoch(c.startS, zone, "d MMMM"))
}

/** Chart labels: a fixed size and left-to-right digits, so axes read the same in Arabic. */
internal fun chartTextStyle(color: Color, sizeSp: Int, bold: Boolean = false) = TextStyle(
    color = color,
    fontSize = sizeSp.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDirection = TextDirection.Ltr,
)

/** Draws [layout] with its first baseline at [baseline], as a canvas `drawText` would. */
internal fun DrawScope.drawLabel(layout: TextLayoutResult, x: Float, baseline: Float) {
    drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
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

/** A whole axis value without decimals, any other with one. */
internal fun fmtAxis(v: Float): String = if (v == kotlin.math.round(v)) v.toInt().toString() else formatFixed(v.toDouble(), 1)

/** A reading: whole from 100 up, one decimal below. */
internal fun fmtValue(v: Float): String = if (v >= 100) v.toInt().toString() else formatFixed(v.toDouble(), 1)

/** X ticks of a time span: every 6 hours for a day, daily for a week, weekly for a month, every 61 days for a year. */
internal fun timeTicks(range: HistoryRange, fromS: Long, toS: Long, zone: TimeZone): List<Pair<Long, String>> {
    val pattern = when (range) {
        HistoryRange.DAY -> "HH:mm"
        HistoryRange.WEEK -> "EEE"
        HistoryRange.MONTH -> "d MMM"
        HistoryRange.YEAR -> "MMM"
    }
    val out = ArrayList<Pair<Long, String>>()
    val first = localDateOf(fromS, zone)
    if (range == HistoryRange.DAY) {
        for (hour in 0..24 step 6) {
            val t = if (hour == 24) {
                startOfDay(first.plus(1, DateTimeUnit.DAY), zone)
            } else {
                LocalDateTime(first.year, first.month, first.day, hour, 0).toInstant(zone).epochSeconds
            }
            if (t in fromS..toS) out += t to formatEpoch(t, zone, pattern)
        }
    } else {
        val stepDays = when (range) {
            HistoryRange.WEEK -> 1
            HistoryRange.MONTH -> 7
            HistoryRange.YEAR -> 61
            HistoryRange.DAY -> error("handled above")
        }
        var day = first
        while (true) {
            val t = startOfDay(day, zone)
            if (t > toS) break
            if (t >= fromS) out += t to formatEpoch(t, zone, pattern)
            day = day.plus(stepDays, DateTimeUnit.DAY)
        }
    }
    return out
}
