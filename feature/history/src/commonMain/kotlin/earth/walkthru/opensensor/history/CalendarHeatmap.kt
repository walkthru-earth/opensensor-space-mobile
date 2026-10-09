package earth.walkthru.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.data.history.startOfDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A month or a year as a calendar of local days: one cell per day, weeks as columns, Monday at the
 * top, coloured by the WHO step of the day's mean (neutral ink without a WHO scale) and faded by its
 * coverage. Empty days are plain cells, estimated days dashed. Tap a day to open it. Month labels
 * along the top.
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
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelPx = with(LocalDensity.current) { 10.sp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, 10) }

    // Whole weeks: the first column starts on the Monday on or before `fromS`.
    val firstDay = localDateOf(fromS, zone)
    val gridStart = firstDay.minus(firstDay.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    val lastDay = localDateOf(toS - 1, zone)
    val weeks = (gridStart.daysUntil(lastDay) / 7 + 1).coerceAtLeast(1)
    val byDay = remember(days) { days.associateBy { it.startS } }
    val rows = 7
    val heightDp = if (weeks > 10) 120.dp else 150.dp

    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp)
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
        var lastMonth: Int = -1
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
                if (m == null) {
                    drawRoundRect(empty, Offset(x0, y0), sz, radius)
                } else {
                    val color = metric.valueColor(m.toDouble(), muted)
                    val alpha = 0.5f + 0.5f * col.coverage
                    if (col.estimated) {
                        drawRoundRect(color.copy(alpha = alpha * 0.3f), Offset(x0, y0), sz, radius)
                        drawRoundRect(color.copy(alpha = alpha), Offset(x0, y0), sz, radius, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                    } else {
                        drawRoundRect(color.copy(alpha = alpha), Offset(x0, y0), sz, radius)
                    }
                }
                // Month label above the first week that holds the 1st (or the first visible day).
                if (dow == 0 || date.day == 1) {
                    val month = date.month.ordinal
                    if (month != lastMonth && (date.day <= 7 || lastMonth == -1)) {
                        lastMonth = month
                        drawLabel(measurer.measure(formatEpoch(startS, zone, "MMM"), labelStyle), week * cellW + pad, labelPx)
                    }
                }
            }
        }
    }
}
