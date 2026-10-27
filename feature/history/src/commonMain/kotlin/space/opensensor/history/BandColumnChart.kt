package space.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.airquality.WhoStep
import space.opensensor.data.history.AirMood
import space.opensensor.data.history.ChartAxes
import space.opensensor.data.history.ChartAxis
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.localDateOf
import space.opensensor.data.history.startOfDay
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.metricLabel
import space.opensensor.designsystem.spokenList
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.chart_bars_title
import space.opensensor.history.resources.chart_guideline_24h
import space.opensensor.history.resources.chart_guideline_note
import space.opensensor.history.resources.chart_guideline_note_simple
import space.opensensor.history.resources.chart_guideline_short
import space.opensensor.history.resources.chart_highest
import space.opensensor.history.resources.chart_highest_at
import space.opensensor.history.resources.chart_mean_daily
import space.opensensor.history.resources.chart_mean_hourly
import space.opensensor.history.resources.chart_mean_weekly
import space.opensensor.history.resources.chart_who_24h
import space.opensensor.history.resources.history_estimated_time
import space.opensensor.history.resources.history_no_readings_short
import space.opensensor.history.resources.history_week_of
import space.opensensor.settings.DetailLevel
import space.opensensor.units.UnitConversion
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem
import space.opensensor.units.displayAxisTicks
import space.opensensor.units.formatFixed
import space.opensensor.units.tickDecimals
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
 * Axis margins are measured from the labels, so large font sizes never clip them, labels that would
 * touch are thinned out ([placeAxisLabels]), and the guideline label wraps to two lines or falls back
 * to a shorter one ([fitGuideLabel]) so it never leaves the canvas.
 *
 * Screen readers get a summary, then one node per bar with a reading, in time order, naming the
 * slot, the mean with its unit, its mood and its WHO step (step only above Simple) and estimated
 * time. Activating a bar selects it, as a touch does.
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
    /** A bar's name for a screen reader when [xLabels] replace time, null to read its value only. */
    barSlot: ((HistoryColumn) -> String)? = null,
    /** The guideline label and its shorter fallbacks, longest first. The first that fits is drawn. */
    guideLabels: List<String> = guidelineLabels(level),
) = BoxWithConstraints(modifier.fillMaxWidth().height(height).semantics { isTraversalGroup = true }) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val gridColor = muted.copy(alpha = 0.18f)
    val density = LocalDensity.current
    val gapPx = with(density) { LABEL_GAP.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(muted) { chartTextStyle(muted, AXIS_LABEL_SP) }
    val capStyle = remember(onSurface) { chartTextStyle(onSurface, CAP_LABEL_SP, bold = true) }
    val guideStyle = remember(onSurface) { chartTextStyle(onSurface, AXIS_LABEL_SP) }
    val recorder = LocalChartDrawRecorder.current

    val axis = remember(columns, metric) { ChartAxes.of(columns, metric) }
    val yMax = axis.max
    val guideline = ChartAxes.guidelineOf(metric)?.takeIf { it <= yMax }
    // Geometry, data and bands stay on the SI values. Ticks are chosen round in the unit shown.
    val yTicks = remember(axis, metric.unit, units.system) { yAxisTicks(axis, metric.unit, units.system) }
    val xTicks = remember(xLabels, range, fromS, toS, zone) { xLabels ?: timeTicks(range, fromS, toS, zone) }
    // Every label the canvas draws, measured and placed once, so a large font size never clips one.
    val canvasSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val labels = remember(canvasSize, axis, fromS, toS, yTicks, xTicks, guideline, guideLabels, labelStyle, capStyle, guideStyle, gapPx, measurer) {
        layoutChartLabels(measurer, canvasSize, axis, fromS, toS, yTicks, xTicks, guideline, guideLabels, labelStyle, capStyle, guideStyle, gapPx)
    }
    val margins = labels.margins
    // The spoken chart: what it shows and its highest bar with the WHO step, built from the same data.
    // A file strip (xLabels) has file indices, not times, so its bars take their names from [barSlot].
    val slot: (@Composable (HistoryColumn) -> String)? = if (xLabels == null) {
        { c -> slotLabel(c, range, zone) }
    } else {
        barSlot?.let { name -> { c -> name(c) } }
    }
    val summary = chartSummary(stringResource(Res.string.chart_bars_title, metricLabel(metric)), columns, metric, units, stepNamed = level.detailed, slot = if (xLabels == null) slot else null)
    val bars = readableBars(columns)
    val separator = listSeparator()
    val estimated = stringResource(Res.string.history_estimated_time)
    val moodTitles = AirMood.entries.associateWith { it.titleText() }
    val stepLabels = WhoStep.entries.associateWith { it.labelText() }
    val spoken = bars.map { c ->
        val m = c.mean ?: 0f
        listOfNotNull(
            slot?.invoke(c),
            fmtValue(units.value(m, metric.unit)) + " " + units.unit(metric.unit),
            metric.moodOf(m.toDouble())?.let { moodTitles[it] },
            // Simple names the mood only, never the WHO step.
            if (level.detailed) metric.stepOf(m.toDouble())?.let { stepLabels[it] } else null,
            estimated.takeIf { c.estimated },
        ).let { spokenList(it, separator) }
    }

    Canvas(
        Modifier
            .matchParentSize()
            .semantics {
                contentDescription = summary
                traversalIndex = -1f
            }
            .pointerInput(columns, fromS, toS, margins) {
                // A tap selects its bar and a sideways drag scrubs. A drag that turns vertical first is
                // never consumed, so the page around the chart still scrolls.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(x: Float): HistoryColumn? {
                        val left = margins.left
                        val width = size.width - left
                        if (width <= 0f) return null
                        val t = fromS + ((x - left) / width * (toS - fromS)).toLong()
                        return columns.firstOrNull { t >= it.startS && t < it.endS }
                    }
                    val slop = viewConfiguration.touchSlop
                    var intent = DragIntent.PENDING
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (intent == DragIntent.PENDING && !change.isConsumed) onSelect(pick(change.position.x))
                            break
                        }
                        // The page took the drag as a scroll.
                        if (change.isConsumed) break
                        if (intent == DragIntent.PENDING) {
                            val moved = change.position - down.position
                            intent = dragIntent(moved.x, moved.y, slop)
                            if (intent == DragIntent.SCROLL) break
                        }
                        if (intent == DragIntent.SCRUB) {
                            onSelect(pick(change.position.x))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val geometry = labels.geometry
        val plot = geometry.plot
        fun x(t: Long) = geometry.x(t)
        fun y(v: Float) = geometry.y(v)
        val zeroY = y(0f)
        // Every label goes through here, so what a test records is what the canvas draws.
        val drawn = ArrayList<DrawnLabel>()
        val marks = ArrayList<Rect>()
        fun put(label: DrawnLabel) {
            drawText(label.layout, topLeft = label.at)
            drawn += label
        }

        for (tick in yTicks) {
            val yy = y(tick.first)
            drawLine(gridColor, Offset(plot.left, yy), Offset(plot.right, yy), 1f)
        }
        labels.y.forEach(::put)

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
            marks += Rect(Offset(x0, topY), Size(w, barHeight))
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
                    moveTo(x0 + w / 2, plot.top - gapPx / 2)
                    lineTo(x0 + w / 2 - 5f, plot.top + 6f)
                    lineTo(x0 + w / 2 + 5f, plot.top + 6f)
                    close()
                }
                drawPath(cap, color)
                if (labelCap[ci]) {
                    val layout = measurer.measure(fmtAxis(units.value(peak ?: m, metric.unit)), capStyle)
                    val tw = layout.size.width.toFloat()
                    put(DrawnLabel(LabelKind.CAP, layout, Offset((x0 + w / 2 - tw / 2).coerceIn(plot.left, max(plot.left, plot.right - tw)), 0f)))
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
            labels.guide?.let(::put)
        }
        selected?.let { s ->
            val cx = (x(s.startS) + x(s.endS)) / 2
            drawLine(onSurface.copy(alpha = 0.6f), Offset(cx, plot.top), Offset(cx, plot.bottom), 1f)
        }
        for (tick in xTicks) {
            val xx = x(tick.first)
            drawLine(gridColor, Offset(xx, plot.bottom), Offset(xx, plot.bottom + gapPx), 1f)
        }
        labels.x.forEach(::put)
        drawLine(muted.copy(alpha = 0.6f), Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1f)
        if (axis.min < 0f) drawLine(muted.copy(alpha = 0.6f), Offset(plot.left, zeroY), Offset(plot.right, zeroY), 1f)
        recorder?.invoke(ChartDrawing(Rect(Offset.Zero, size), plot, drawn, marks))
    }
    // One node per bar with a reading, laid over the canvas at the drawn LTR positions. Semantics only
    // (no pointer input), so a finger still lands on the canvas. Activating a bar selects it.
    ExactTouchBounds {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val left = with(density) { margins.left.toDp() }
            val top = with(density) { margins.top.toDp() }
            val plotHeight = maxHeight - top - with(density) { margins.bottom.toDp() }
            val plotWidth = maxWidth - left
            val span = (toS - fromS).toFloat()
            if (span > 0f && plotWidth > 0.dp && plotHeight > 0.dp) {
                for ((i, c) in bars.withIndex()) {
                    val description = spoken[i]
                    val isSel = selected != null && selected.startS == c.startS
                    Box(
                        Modifier
                            .absoluteOffset(x = left + plotWidth * ((c.startS - fromS) / span), y = top)
                            .size(plotWidth * ((c.endS - c.startS) / span), plotHeight)
                            .semantics {
                                role = Role.Button
                                contentDescription = description
                                this.selected = isSel
                                traversalIndex = i.toFloat()
                                onClick {
                                    onSelect(c)
                                    true
                                }
                            },
                    )
                }
            }
        }
    }
}

/** Space between an axis label and the plot. */
internal val LABEL_GAP = 4.dp

/** Axis and guideline label size. It scales with the font size, and the margins are measured from it. */
internal const val AXIS_LABEL_SP = 11

/** The off-scale cap label size. */
internal const val CAP_LABEL_SP = 10

/** The plot inside a canvas of [canvas] px, and where a time or a value lands on it. */
internal class ChartGeometry(canvas: Size, margins: ChartMargins, private val axis: ChartAxis, private val fromS: Long, private val toS: Long) {
    val canvas: Rect = Rect(Offset.Zero, canvas)
    val plot: Rect = Rect(margins.left, margins.top, canvas.width, canvas.height - margins.bottom)

    fun x(t: Long): Float = plot.left + (t - fromS).toFloat() / (toS - fromS) * plot.width

    fun y(v: Float): Float = plot.bottom - axis.fraction(v) * plot.height
}

/** Each axis label kept, as its index and top left, and where the guideline label goes, null when it has no line. */
internal data class AxisLabels(val y: List<Pair<Int, Offset>>, val x: List<Pair<Int, Offset>>, val guide: Offset?)

/**
 * Where the axis labels go. Every label stays inside the canvas, and a label that would touch the one
 * kept before it is skipped (its gridline stays), so at a large font size the axes thin out instead of
 * piling up or clipping. The guideline label sits above its line, or below it when there is no room.
 */
internal fun placeAxisLabels(
    geometry: ChartGeometry,
    yAt: List<Float>,
    ySizes: List<IntSize>,
    xAt: List<Long>,
    xSizes: List<IntSize>,
    guideline: Float?,
    guideSize: IntSize,
    gap: Float,
): AxisLabels {
    val canvas = geometry.canvas
    val plot = geometry.plot
    val y = ArrayList<Pair<Int, Offset>>()
    var lastTop = Float.POSITIVE_INFINITY
    for ((i, v) in yAt.withIndex()) {
        val h = ySizes[i].height.toFloat()
        val top = (geometry.y(v) - h / 2f).coerceIn(canvas.top, max(canvas.top, canvas.bottom - h))
        // Values rise up the axis, so each label must end above the last one kept.
        if (top + h + gap <= lastTop) {
            y += i to Offset(canvas.left, top)
            lastTop = top
        }
    }
    val x = ArrayList<Pair<Int, Offset>>()
    var lastRight = Float.NEGATIVE_INFINITY
    for ((i, t) in xAt.withIndex()) {
        val w = xSizes[i].width.toFloat()
        val left = (geometry.x(t) - w / 2f).coerceIn(plot.left, max(plot.left, plot.right - w))
        if (left >= lastRight + gap) {
            x += i to Offset(left, plot.bottom + 2 * gap)
            lastRight = left + w
        }
    }
    val guide = guideline?.let { g ->
        val line = geometry.y(g)
        val w = guideSize.width.toFloat()
        val h = guideSize.height.toFloat()
        val above = line - h - gap / 2
        val top = if (above >= canvas.top) above else (line + gap / 2).coerceAtMost(max(canvas.top, canvas.bottom - h))
        Offset(max(plot.left, plot.right - w), top)
    }
    return AxisLabels(y, x, guide)
}

/** What a label is on the chart. */
internal enum class LabelKind { Y, X, GUIDE, CAP }

/** One label the chart draws, its layout and its top left in canvas px. */
internal class DrawnLabel(val kind: LabelKind, val layout: TextLayoutResult, val at: Offset) {
    val rect: Rect get() = Rect(at, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
}

/** Every axis and guideline label of a chart, measured and placed, with the plot they sit around. */
internal class ChartLabels(val geometry: ChartGeometry, val margins: ChartMargins, val y: List<DrawnLabel>, val x: List<DrawnLabel>, val guide: DrawnLabel?)

/**
 * Measures the labels, sizes the margins from them and places them with [placeAxisLabels]. The canvas
 * draws exactly these, and the font scale test checks them through [LocalChartDrawRecorder].
 */
internal fun layoutChartLabels(
    measurer: TextMeasurer,
    canvas: Size,
    axis: ChartAxis,
    fromS: Long,
    toS: Long,
    yTicks: List<Pair<Float, String>>,
    xTicks: List<Pair<Long, String>>,
    guideline: Float?,
    guideTexts: List<String>,
    labelStyle: TextStyle,
    capStyle: TextStyle,
    guideStyle: TextStyle,
    gap: Float,
): ChartLabels {
    val yLayouts = yTicks.map { measurer.measure(it.second, labelStyle) }
    val xLayouts = xTicks.map { measurer.measure(it.second, labelStyle) }
    val margins = chartMargins(
        yLabelWidths = yLayouts.map { it.size.width },
        xLabelHeight = xLayouts.maxOfOrNull { it.size.height } ?: measurer.measure("0", labelStyle).size.height,
        capHeight = measurer.measure("0", capStyle).size.height,
        gap = gap,
    )
    val geometry = ChartGeometry(canvas, margins, axis, fromS, toS)
    val guideLayout = guideline?.let { fitGuideLabel(measurer, guideTexts, guideStyle, geometry.plot.width, geometry.canvas.height) }
    val placed = placeAxisLabels(
        geometry,
        yTicks.map { it.first },
        yLayouts.map { it.size },
        xTicks.map { it.first },
        xLayouts.map { it.size },
        guideline.takeIf { guideLayout != null },
        guideLayout?.size ?: IntSize.Zero,
        gap,
    )
    return ChartLabels(
        geometry,
        margins,
        placed.y.map { (i, at) -> DrawnLabel(LabelKind.Y, yLayouts[i], at) },
        placed.x.map { (i, at) -> DrawnLabel(LabelKind.X, xLayouts[i], at) },
        guideLayout?.let { layout -> placed.guide?.let { DrawnLabel(LabelKind.GUIDE, layout, it) } },
    )
}

/**
 * The first of [texts] that fits [width] px, on one line or wrapped to two, and [height] px. Null when
 * none fits, then the dashed line goes unlabelled and the note under the chart still names it.
 */
internal fun fitGuideLabel(measurer: TextMeasurer, texts: List<String>, style: TextStyle, width: Float, height: Float): TextLayoutResult? {
    if (width < 1f) return null
    for (text in texts) {
        val line = measurer.measure(text, style, maxLines = 1)
        if (line.size.width <= width && line.size.height <= height) return line
        // Wrapped lines hug the right edge, where a one line label sits.
        val wrapped = measurer.measure(text, style.copy(textAlign = TextAlign.Right), maxLines = 2, constraints = Constraints(maxWidth = width.toInt()))
        if (!wrapped.hasVisualOverflow && wrapped.size.width <= width && wrapped.size.height <= height) return wrapped
    }
    return null
}

/**
 * Everything one frame of a chart drew, the canvas, the plot, each label and each mark (a bar or a
 * calendar cell with a reading), the marks in the order of their screen reader nodes.
 */
internal class ChartDrawing(val canvas: Rect, val plot: Rect, val labels: List<DrawnLabel>, val marks: List<Rect> = emptyList())

/** A test hook that receives each frame the chart draws. Null in the app. */
internal val LocalChartDrawRecorder = staticCompositionLocalOf<((ChartDrawing) -> Unit)?> { null }

/** The plot's inset from the canvas edges in px, room for the y labels, the x labels and an off-scale cap. */
internal data class ChartMargins(val left: Float, val top: Float, val bottom: Float)

/** Margins that fit the widest y label, the tallest x label (under a tick of [gap]) and a cap label. */
internal fun chartMargins(yLabelWidths: List<Int>, xLabelHeight: Int, capHeight: Int, gap: Float): ChartMargins = ChartMargins(
    left = (yLabelWidths.maxOrNull() ?: 0) + gap,
    top = capHeight + gap,
    bottom = xLabelHeight + 2 * gap,
)

/** The bars a screen reader can read, those with a mean, in time order. Empty slots are left out. */
internal fun readableBars(columns: List<HistoryColumn>): List<HistoryColumn> =
    columns.filter { it.mean?.isFinite() == true }.sortedBy { it.startS }

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
): String = spokenList(listOf(title, chartHighest(columns, metric, units, stepNamed, slot) ?: stringResource(Res.string.history_no_readings_short)), listSeparator())

/** The label on the guideline line and its shorter fallbacks, longest first. Simple never names WHO. */
internal fun guidelineLabelsRes(level: DetailLevel): List<StringResource> = listOfNotNull(
    Res.string.chart_who_24h.takeIf { level.detailed },
    Res.string.chart_guideline_24h,
    Res.string.chart_guideline_short,
)

/** The guideline label texts for [level], longest first, the chart draws the first that fits. */
@Composable
fun guidelineLabels(level: DetailLevel): List<String> = guidelineLabelsRes(level).map { stringResource(it) }

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

/** Chart labels in `sp`, so they follow the font size, with left-to-right digits, so axes read the same in Arabic. */
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

/** What a drag over the chart is for, decided once it passes the touch slop. */
internal enum class DragIntent { PENDING, SCRUB, SCROLL }

/**
 * A drag of ([dx], [dy]) from its first touch. Under [slop] it is still a tap. Past it, a mostly
 * sideways drag scrubs the bars and anything else belongs to the page scroll.
 */
internal fun dragIntent(dx: Float, dy: Float, slop: Float): DragIntent = when {
    abs(dx) <= slop && abs(dy) <= slop -> DragIntent.PENDING
    abs(dx) > abs(dy) -> DragIntent.SCRUB
    else -> DragIntent.SCROLL
}
