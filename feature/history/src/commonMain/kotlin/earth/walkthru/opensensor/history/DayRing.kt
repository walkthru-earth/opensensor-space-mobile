package earth.walkthru.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.opensensor.designsystem.AirQualityPeacock
import earth.walkthru.opensensor.designsystem.listSeparator
import earth.walkthru.opensensor.designsystem.spokenList
import earth.walkthru.opensensor.history.resources.Res
import earth.walkthru.opensensor.history.resources.chart_average_value
import earth.walkthru.opensensor.history.resources.chart_ring_title
import earth.walkthru.opensensor.history.resources.history_day_coverage
import earth.walkthru.opensensor.history.resources.history_no_readings_short
import earth.walkthru.opensensor.history.resources.history_no_value
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The day as a clock: one segment per elapsed hour (23 or 25 across DST), midnight at the top, each
 * coloured by its WHO step (neutral ink without a scale), faded by coverage, dashed on estimated
 * time, a tick for "now" when the day is today, and the day's average with its mood in the middle.
 */
@Composable
internal fun DayRing(
    columns: List<HistoryColumn>,
    metric: MetricSpec,
    dayStartS: Long,
    dayEndS: Long,
    mean: Float?,
    coverage: Float,
    nowS: Long?,
    selected: HistoryColumn?,
    zone: TimeZone,
    units: UnitFormatter,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, 11) }
    val mood = mean?.let { metric.moodOf(it.toDouble()) }
    // One spoken element for the ring and its centre: the day's average with its mood and WHO step,
    // the coverage, then the highest hour. The hour colours alone would say nothing to a screen reader.
    val average = units.value(mean, metric.unit)?.let { stringResource(Res.string.chart_average_value, fmtValue(it), units.unit(metric.unit)) }
    val description = spokenList(
        listOfNotNull(
            stringResource(Res.string.chart_ring_title, metric.label),
            average ?: stringResource(Res.string.history_no_readings_short),
            mood?.titleText(),
            mood?.step?.labelText(),
            if (mean != null) stringResource(Res.string.history_day_coverage, (coverage * 100).toInt()) else null,
            chartHighest(columns, metric, units) { c -> slotLabel(c, HistoryRange.DAY, zone) },
        ),
        listSeparator(),
    )

    Box(modifier.size(size).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2 - labelPx * 1.4f
            val thickness = outer * 0.22f
            val r = outer - thickness / 2
            val daySeconds = (dayEndS - dayStartS).toFloat()
            fun angle(s: Long): Float = ((s - dayStartS) / daySeconds) * 360f - 90f // midnight at the top
            drawCircle(empty, r, c, style = Stroke(thickness))
            for (col in columns) {
                val m = col.mean ?: continue
                val start = angle(col.startS)
                val sweep = ((col.endS - col.startS) / daySeconds) * 360f
                val color = metric.valueColor(m.toDouble(), muted)
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
            // Hour marks 00, 06, 12 and 18 in the local zone.
            val day = localDateOf(dayStartS, zone)
            for (h in listOf(0, 6, 12, 18)) {
                val s = LocalDateTime(day.year, day.month, day.day, h, 0).toInstant(zone).epochSeconds
                val a = angle(s) * PI.toFloat() / 180f
                val lr = outer + labelPx * 0.8f
                val layout = measurer.measure(h.toString().padStart(2, '0'), labelStyle)
                val w = layout.size.width
                drawLabel(layout, c.x + cos(a) * lr - w / 2, c.y + sin(a) * lr + labelPx * 0.35f)
                drawLine(muted.copy(alpha = 0.5f), Offset(c.x + cos(a) * (outer + 2f), c.y + sin(a) * (outer + 2f)), Offset(c.x + cos(a) * (outer + 8f), c.y + sin(a) * (outer + 8f)), 1.5f)
            }
            if (nowS != null && nowS >= dayStartS && nowS < dayEndS) {
                val a = angle(nowS) * PI.toFloat() / 180f
                drawLine(onSurface, Offset(c.x + cos(a) * (r - thickness * 0.8f), c.y + sin(a) * (r - thickness * 0.8f)), Offset(c.x + cos(a) * (r + thickness * 0.8f), c.y + sin(a) * (r + thickness * 0.8f)), 2.5f, cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size * 0.5f), verticalArrangement = Arrangement.Center) {
            if (mood != null) AirQualityPeacock(mood, Modifier.size((size * 0.16f).coerceAtLeast(48.dp)), contentDescription = mood.titleText())
            Text(
                units.value(mean, metric.unit)?.let(::fmtValue) ?: stringResource(Res.string.history_no_value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = mood?.textColorOnSurface() ?: onSurface,
            )
            Text(if (mean == null) stringResource(Res.string.history_no_readings_short) else units.unit(metric.unit), style = MaterialTheme.typography.labelSmall, color = muted)
            if (mean != null) Text(stringResource(Res.string.history_day_coverage, (coverage * 100).toInt()), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
