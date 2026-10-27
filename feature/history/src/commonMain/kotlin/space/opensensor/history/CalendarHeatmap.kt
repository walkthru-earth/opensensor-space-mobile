package space.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.localDateOf
import space.opensensor.data.history.startOfDay
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.metricLabel
import space.opensensor.designsystem.spokenList
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import space.opensensor.family.air.moodOf
import space.opensensor.family.air.stepOf
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.chart_calendar_title
import space.opensensor.history.resources.chart_days_with_readings
import space.opensensor.history.resources.history_estimated_time
import space.opensensor.units.UnitFormatter

/**
 * A month or a year as a calendar of local days: one cell per day, weeks as columns, Monday at the
 * top, coloured by the WHO step of the day's mean (neutral ink without a WHO scale) and faded by its
 * coverage. The colour is never the only cue. A day fills its cell from the bottom by its WHO step
 * ([whoStepShare]), and every cell has an `outline` border that keeps 3 to 1 against the card. Empty
 * days are plain cells, estimated days dashed. Tap a day to open it. Month rows are at least
 * [MONTH_ROW] tall, so each day is a full touch target. Month labels run along the top, and a label
 * that would run into the one before it is left out, so long or large month names never overlap.
 *
 * Screen readers get a summary of the calendar, then one button per day the tap opens, in date order,
 * naming the date, the mean with its mood and WHO step (step only when `showStep`, Simple never
 * names WHO), and estimated time. Those buttons are
 * semantics only (no pointer input), so a finger still lands on the canvas as before.
 */
@Composable
internal fun CalendarHeatmap(
    days: List<HistoryColumn>,
    metric: MetricSpec,
    fromS: Long,
    toS: Long,
    onPickDay: (HistoryColumn) -> Unit,
    zone: TimeZone,
    modifier: Modifier = Modifier,
    units: UnitFormatter = UnitFormatter.Metric,
    showStep: Boolean = true,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (border, empty) = markColors(MaterialTheme.colorScheme)
    val labelPx = with(LocalDensity.current) { 10.sp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, 10) }
    val recorder = LocalChartDrawRecorder.current

    // Whole weeks: the first column starts on the Monday on or before `fromS`.
    val firstDay = localDateOf(fromS, zone)
    val gridStart = firstDay.minus(firstDay.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val lastDay = localDateOf(toS - 1, zone)
    val weeks = (gridStart.daysUntil(lastDay) / 7 + 1).coerceAtLeast(1)
    val byDay = remember(days) { days.associateBy { it.startS } }
    val readable = remember(days, fromS, toS) { readableDays(days, fromS, toS) }
    val rows = 7
    val gridTopDp = with(LocalDensity.current) { (labelPx * 1.6f).toDp() }
    val heightDp = calendarHeight(weeks, gridTopDp)
    val shownDays = firstDay.daysUntil(lastDay) + 1
    val daysWithReadings = readable.size
    val separator = listSeparator()
    val summary = listOfNotNull(
        stringResource(Res.string.chart_calendar_title, metricLabel(metric)),
        stringResource(Res.string.chart_days_with_readings, daysWithReadings, shownDays),
        chartHighest(days.filter { it.startS >= fromS && it.startS < toS }, metric, units, stepNamed = showStep) { c -> formatEpoch(c.startS, zone, DAY_SPOKEN) },
    ).let { spokenList(it, separator) }
    val estimated = stringResource(Res.string.history_estimated_time)
    val moodTitles = AirMood.entries.associateWith { it.titleText() }
    val stepLabels = WhoStep.entries.associateWith { it.labelText() }

    Box(modifier.fillMaxWidth().height(heightDp).semantics { isTraversalGroup = true }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .semantics {
                    contentDescription = summary
                    traversalIndex = -1f
                }
                .pointerInput(days, weeks) {
                    detectTapGestures { pos ->
                        val cellW = size.width / weeks.toFloat()
                        val gridTop = labelPx * 1.6f
                        val cellH = (size.height - gridTop) / rows
                        val week = (pos.x / cellW).toInt()
                        val dow = ((pos.y - gridTop) / cellH).toInt()
                        if (week !in 0 until weeks || dow !in 0 until rows) return@detectTapGestures
                        val date = gridStart.plus(week * 7 + dow, DateTimeUnit.DAY)
                        byDay[startOfDay(date, zone)]?.let(onPickDay)
                    }
                },
        ) {
            val cellW = size.width / weeks
            val gridTop = labelPx * 1.6f
            val cellH = (size.height - gridTop) / rows
            val pad = (minOf(cellW, cellH) * 0.15f).coerceAtLeast(1f)
            val radius = CornerRadius((minOf(cellW, cellH) * 0.2f).coerceAtMost(6f))
            val borderPx = 1.dp.toPx()
            val labelGapPx = 4.dp.toPx()
            var lastMonth: Int = -1
            var labelEnd = Float.NEGATIVE_INFINITY
            val marks = ArrayList<Rect>()
            for (week in 0 until weeks) {
                for (dow in 0 until rows) {
                    val date: LocalDate = gridStart.plus(week * 7 + dow, DateTimeUnit.DAY)
                    val startS = startOfDay(date, zone)
                    if (startS < fromS || startS >= toS) continue
                    val col = byDay[startS]
                    val x0 = week * cellW + pad
                    val y0 = gridTop + dow * cellH + pad
                    val sz = Size(cellW - 2 * pad, cellH - 2 * pad)
                    val m = col?.mean
                    if (startS in readable) marks += Rect(Offset(x0, y0), sz)
                    drawRoundRect(empty, Offset(x0, y0), sz, radius)
                    if (m != null) {
                        val color = metric.valueColor(m.toDouble(), muted)
                        val alpha = 0.5f + 0.5f * col.coverage
                        // The fill rises from the bottom of the cell by the day's WHO step.
                        val fillH = sz.height * whoStepShare(metric.stepOf(m.toDouble()))
                        val fillTop = Offset(x0, y0 + sz.height - fillH)
                        drawRoundRect(color.copy(alpha = if (col.estimated) alpha * 0.3f else alpha), fillTop, Size(sz.width, fillH), radius)
                        val dash = if (col.estimated) PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null
                        drawRoundRect(border, Offset(x0, y0), sz, radius, style = Stroke(if (col.estimated) 1.5f else borderPx, pathEffect = dash))
                    }
                    // Month label above the first week that holds the 1st (or the first visible day).
                    if (dow == 0 || date.day == 1) {
                        val month = date.month.ordinal
                        if (month != lastMonth && (date.day <= 7 || lastMonth == -1)) {
                            lastMonth = month
                            val x = week * cellW + pad
                            if (x >= labelEnd + labelGapPx) {
                                val layout = measurer.measure(formatEpoch(startS, zone, "MMM"), labelStyle)
                                drawLabel(layout, x, labelPx)
                                labelEnd = x + layout.size.width
                            }
                        }
                    }
                }
            }
            recorder?.invoke(ChartDrawing(Rect(Offset.Zero, size), Rect(Offset.Zero, size), emptyList(), marks))
        }
        // The day cells for screen readers, laid over the canvas at the same LTR positions as the drawing,
        // each node exactly its cell, never grown to the minimum touch size (#141).
        ExactTouchBounds {
            BoxWithConstraints(Modifier.fillMaxWidth().height(heightDp)) {
                val cellW = maxWidth / weeks
                val gridTop = gridTopDp
                val cellH = (maxHeight - gridTop) / rows
                for (week in 0 until weeks) {
                    for (dow in 0 until rows) {
                        val date: LocalDate = gridStart.plus(week * 7 + dow, DateTimeUnit.DAY)
                        val startS = startOfDay(date, zone)
                        if (startS < fromS || startS >= toS) continue
                        // Only days with a reading get a button (P68). Empty days stay out of the swipe order.
                        val col = readable[startS] ?: continue
                        val m = col.mean ?: continue
                        val mood = metric.moodOf(m.toDouble())
                        val description = listOfNotNull(
                            formatEpoch(startS, zone, DAY_SPOKEN),
                            fmtValue(units.value(m, metric.unit)) + " " + units.unit(metric.unit),
                            mood?.let { moodTitles[it] },
                            if (showStep) metric.stepOf(m.toDouble())?.let { stepLabels[it] } else null,
                            estimated.takeIf { col.estimated },
                        ).let { spokenList(it, separator) }
                        val order = (week * 7 + dow).toFloat()
                        Box(
                            Modifier
                                .absoluteOffset(x = cellW * week, y = gridTop + cellH * dow)
                                .size(cellW, cellH)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = description
                                    traversalIndex = order
                                    onClick {
                                        onPickDay(col)
                                        true
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The days a screen reader can open, keyed by their start. A day column with no mean is an empty
 * day, so only days inside the shown range that have a reading are listed.
 */
internal fun readableDays(days: List<HistoryColumn>, fromS: Long, toS: Long): Map<Long, HistoryColumn> =
    days.filter { it.mean != null && it.startS >= fromS && it.startS < toS }.associateBy { it.startS }

/**
 * The calendar's height under [gridTop], the month label row. A month (up to 6 weeks) gives each
 * row [MONTH_ROW], so every day is a full touch target. A year keeps a compact strip, its days are
 * reached through the per day screen reader nodes and by opening a month.
 */
internal fun calendarHeight(weeks: Int, gridTop: Dp): Dp = if (weeks > MONTH_MAX_WEEKS) YEAR_HEIGHT else gridTop + MONTH_ROW * 7

/** The smallest row height of a month calendar, the minimum touch target. */
internal val MONTH_ROW = 48.dp

private const val MONTH_MAX_WEEKS = 10
private val YEAR_HEIGHT = 120.dp

/** A day spoken in full, so a screen reader says "Tuesday 3 March", not "3 Mar". */
private const val DAY_SPOKEN = "EEEE d MMMM"
