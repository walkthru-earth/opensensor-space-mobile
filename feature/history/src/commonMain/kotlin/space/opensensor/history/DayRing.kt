package space.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.localDateOf
import space.opensensor.designsystem.AirQualityPeacock
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.metricLabel
import space.opensensor.designsystem.spokenList
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import space.opensensor.family.air.moodOf
import space.opensensor.family.air.stepOf
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.chart_average_value
import space.opensensor.history.resources.chart_ring_title
import space.opensensor.history.resources.history_day_coverage
import space.opensensor.history.resources.history_estimated_time
import space.opensensor.history.resources.history_no_readings_short
import space.opensensor.history.resources.history_no_value
import space.opensensor.units.UnitFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The day as a clock: one segment per elapsed hour (23 or 25 across DST), midnight at the top, each
 * coloured by its WHO step (neutral ink without a scale), faded by coverage, dashed on estimated
 * time, a tick for "now" when the day is today, and the day's average with its mood in the middle.
 *
 * The colour is never the only cue. Each hour grows outward from the inner edge by its WHO step
 * ([whoStepShare]), so a cleaner hour is a shorter bar, and every bar has an `outline` border that
 * keeps 3 to 1 against the card and the track ([markColors]), drawn under the track so the step colour
 * reads true. Hour labels get the room their measured size needs, and at a font
 * scale of [STACKED_FONT_SCALE] or more the value text moves below the ring, so nothing clips at
 * 200 percent.
 *
 * Screen readers get the day summary first, then one node per hour with a reading, in time order,
 * naming the hour, the value with its unit, the mood, the WHO step (only when `showStep`, Simple never
 * names WHO) and estimated time. The hour nodes are semantics only, laid over the drawn segments.
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
    showStep: Boolean = true,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val (border, empty) = markColors(MaterialTheme.colorScheme)
    val density = LocalDensity.current
    val stacked = density.fontScale >= STACKED_FONT_SCALE
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, 11) }
    // The room an hour label takes past the ring, from its measured size, so it grows with the font.
    val labelExtentPx = remember(measurer, labelStyle, density) { measurer.measure("00", labelStyle).size.let { maxOf(it.width, it.height) }.toFloat() }
    val tickPx = with(density) { RING_TICK.toPx() }
    val marginPx = tickPx + labelExtentPx + with(density) { 2.dp.toPx() }
    val borderPx = with(density) { RING_BORDER.toPx() }
    val mood = mean?.let { metric.moodOf(it.toDouble()) }
    // The summary for the ring and its centre: the day's average with its mood and WHO step
    // (the step only when `showStep`, Simple never names WHO), the coverage, then the highest hour.
    val average = units.value(mean, metric.unit)?.let { stringResource(Res.string.chart_average_value, fmtValue(it), units.unit(metric.unit)) }
    val separator = listSeparator()
    val description = spokenList(
        listOfNotNull(
            stringResource(Res.string.chart_ring_title, metricLabel(metric)),
            average ?: stringResource(Res.string.history_no_readings_short),
            mood?.titleText(),
            if (showStep) mood?.step?.labelText() else null,
            if (mean != null) stringResource(Res.string.history_day_coverage, (coverage * 100).toInt()) else null,
            chartHighest(columns, metric, units, stepNamed = showStep) { c -> slotLabel(c, HistoryRange.DAY, zone) },
        ),
        separator,
    )
    val estimated = stringResource(Res.string.history_estimated_time)
    val moodTitles = AirMood.entries.associateWith { it.titleText() }
    val stepLabels = WhoStep.entries.associateWith { it.labelText() }
    val hours = columns.filter { it.mean?.isFinite() == true }.sortedBy { it.startS }
    val hourDescriptions = hours.map { col ->
        val v = col.mean!!
        val m = v.toDouble()
        listOfNotNull(
            slotLabel(col, HistoryRange.DAY, zone),
            fmtValue(units.value(v, metric.unit)) + " " + units.unit(metric.unit),
            metric.moodOf(m)?.let { moodTitles[it] },
            if (showStep) metric.stepOf(m)?.let { stepLabels[it] } else null,
            estimated.takeIf { col.estimated },
        ).let { spokenList(it, separator) }
    }
    val daySeconds = (dayEndS - dayStartS).toFloat()
    fun angle(s: Long): Float = ((s - dayStartS) / daySeconds) * 360f - 90f // midnight at the top

    Column(modifier.semantics { isTraversalGroup = true }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().semantics {
                    contentDescription = description
                    traversalIndex = -1f
                },
            ) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val outer = this.size.minDimension / 2 - marginPx
                val thickness = outer * RING_THICKNESS
                val inner = outer - thickness
                drawCircle(empty, inner + thickness / 2, c, style = Stroke(thickness))
                for (col in columns) {
                    val m = col.mean ?: continue
                    if (!m.isFinite()) continue
                    val start = angle(col.startS)
                    val sweep = ((col.endS - col.startS) / daySeconds) * 360f - 0.6f
                    val color = metric.valueColor(m.toDouble(), muted)
                    val alpha = 0.3f + 0.7f * col.coverage
                    val isSel = selected != null && selected.startS == col.startS
                    // The bar grows outward from the inner edge by its WHO step, a selected hour by a quarter more.
                    val w = thickness * whoStepShare(metric.stepOf(m.toDouble())) * (if (isSel) 1.25f else 1f)
                    val mid = inner + w / 2
                    val arcTopLeft = Offset(c.x - mid, c.y - mid)
                    val arcSize = Size(2 * mid, 2 * mid)
                    // Border, then the track, then the step colour, so a see-through colour and the gaps in a
                    // dashed hour show the track and never the border.
                    drawArc(border, start, sweep, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(w + 2 * borderPx))
                    drawArc(empty, start, sweep, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(w))
                    drawArc(
                        color = color.copy(alpha = alpha),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(w, pathEffect = if (col.estimated) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null),
                    )
                }
                // Hour marks 00, 06, 12 and 18 in the local zone, each label centred past its tick.
                val day = localDateOf(dayStartS, zone)
                for (h in listOf(0, 6, 12, 18)) {
                    val s = LocalDateTime(day.year, day.month, day.day, h, 0).toInstant(zone).epochSeconds
                    val a = angle(s) * PI.toFloat() / 180f
                    val lr = outer + tickPx + labelExtentPx / 2
                    val layout = measurer.measure(h.toString().padStart(2, '0'), labelStyle)
                    val x = c.x + cos(a) * lr - layout.size.width / 2
                    val top = c.y + sin(a) * lr - layout.size.height / 2
                    drawLabel(layout, x, top + layout.firstBaseline)
                    drawLine(muted.copy(alpha = 0.5f), Offset(c.x + cos(a) * (outer + 2f), c.y + sin(a) * (outer + 2f)), Offset(c.x + cos(a) * (outer + tickPx), c.y + sin(a) * (outer + tickPx)), 1.5f)
                }
                if (nowS != null && nowS >= dayStartS && nowS < dayEndS) {
                    val a = angle(nowS) * PI.toFloat() / 180f
                    val r = inner + thickness / 2
                    drawLine(onSurface, Offset(c.x + cos(a) * (r - thickness * 0.8f), c.y + sin(a) * (r - thickness * 0.8f)), Offset(c.x + cos(a) * (r + thickness * 0.8f), c.y + sin(a) * (r + thickness * 0.8f)), 2.5f, cap = StrokeCap.Round)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(size * 0.5f).clearAndSetSemantics { },
                verticalArrangement = Arrangement.Center,
            ) {
                if (mood != null) AirQualityPeacock(mood, Modifier.size((size * 0.16f).coerceAtLeast(48.dp)), contentDescription = null)
                if (!stacked) RingValue(mean, mood, metric, coverage, units)
            }
            // One node per hour with a reading, centred on its segment, each exactly its size (#141).
            ExactTouchBounds {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val minDim = minOf(maxWidth, maxHeight)
                    val outer = minDim / 2 - with(density) { marginPx.toDp() }
                    val thickness = outer * RING_THICKNESS
                    val r = outer - thickness / 2
                    hours.forEachIndexed { i, col ->
                        val a = angle((col.startS + col.endS) / 2) * PI.toFloat() / 180f
                        val side = thickness
                        Box(
                            Modifier
                                .absoluteOffset(x = maxWidth / 2 + r * cos(a) - side / 2, y = maxHeight / 2 + r * sin(a) - side / 2)
                                .size(side)
                                .semantics {
                                    contentDescription = hourDescriptions[i]
                                    traversalIndex = i.toFloat()
                                },
                        )
                    }
                }
            }
        }
        if (stacked) {
            Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                RingValue(mean, mood, metric, coverage, units)
            }
        }
    }
}

/** The day's average, its unit and the coverage, in the ring's centre or below it at large font scales. */
@Composable
private fun RingValue(mean: Float?, mood: AirMood?, metric: MetricSpec, coverage: Float, units: UnitFormatter) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        units.value(mean, metric.unit)?.let(::fmtValue) ?: stringResource(Res.string.history_no_value),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = mood?.textColorOnSurface() ?: MaterialTheme.colorScheme.onSurface,
    )
    Text(if (mean == null) stringResource(Res.string.history_no_readings_short) else units.unit(metric.unit), style = MaterialTheme.typography.labelSmall, color = muted)
    if (mean != null) Text(stringResource(Res.string.history_day_coverage, (coverage * 100).toInt()), style = MaterialTheme.typography.labelSmall, color = muted)
}

/** At this font scale or more the ring's value text moves below the ring, where it has room. */
internal const val STACKED_FONT_SCALE = 1.3f

/** The ring's track width as a share of its outer radius. */
private const val RING_THICKNESS = 0.22f

private val RING_TICK = 6.dp
private val RING_BORDER = 1.dp
