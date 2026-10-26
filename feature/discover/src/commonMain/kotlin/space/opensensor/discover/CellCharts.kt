package space.opensensor.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.spokenList
import space.opensensor.publicfeed.CellScale
import space.opensensor.publicfeed.FeedProperty
import space.opensensor.publicfeed.FeedWindow
import space.opensensor.publicfeed.SequentialRamp
import space.opensensor.publicfeed.StepScale
import space.opensensor.units.UnitFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Sheet charts, drawn with Canvas in common code so Android and iOS match. The feed has no hourly
 * history per cell, so nothing here is a time series. Each chart shows one window's statistics.
 * Marks are ink (onSurface) and grey. The only hues are the map's own scale, the fixed WHO step
 * colours for PM2.5 and PM10 or viridis for the rest, and every chart carries a spoken summary of
 * its values. Missing values are left out, never drawn as zero. The background stays neutral, no
 * colour bands sit behind the data.
 */

private val BAR_AREA = 56.dp
private val AXIS = 8.dp
private val BAND = 4.dp
private const val ANIM_MS = 350
private val GUIDE_DASH = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

/** The window's name. A [daily] feed's DAY window is its latest day, not the last 24 hours. */
internal fun windowLabel(window: FeedWindow, strings: DiscoverStrings, daily: Boolean = false): String = when (window) {
    FeedWindow.LAST_HOUR -> strings.windowLastHour
    FeedWindow.DAY -> if (daily) strings.windowLatestDay else strings.window24Hours
    FeedWindow.WEEK -> strings.window7Days
}

/**
 * Where one area sits among the areas in view. Bars count the areas by mean. For a property with a
 * WHO scale the axis runs from zero to a WHO edge, each bar takes its step colour, the guideline is
 * a labelled dashed line and values past the axis are capped at its end and marked. Otherwise grey
 * bars sit on an axis drawn as the map's viridis scale. [here] (when set) is a bold marker with its
 * min to max band below the axis. Without [here] it is the distribution alone, for the list of areas.
 */
@Composable
internal fun SpreadChart(
    values: List<Double>,
    property: FeedProperty,
    scale: CellScale?,
    strings: DiscoverStrings,
    modifier: Modifier = Modifier,
    here: Double? = null,
    hereMin: Double? = null,
    hereMax: Double? = null,
    clearSignal: Int = 0,
    onHighlight: (ValueBand?) -> Unit = {},
) {
    val domain = remember(values, scale) { ChartDomain.forScale(scale, values) } ?: return
    // [steps] names and colours the bars. [axis] is set when the chart runs from zero to an edge.
    val steps = (scale as? CellScale.Steps)?.scale
    val axis = stepAxisOf(scale)
    val guideline = axis?.guideline
    val bins = remember(domain, scale, values.size) { chartBins(scale, domain, values.size) }
    val counts = remember(values, domain, bins, steps) { histogram(values, domain, bins, upperInclusive = steps != null) }
    val hereBin = here?.let { binOf(it, domain, bins, upperInclusive = steps != null) }
    // The bar under the finger while it touches the chart, and the bar a tap pinned. Either one filters
    // the map and the list to that bar's areas. A new axis or [clearSignal] drops the pin.
    var scrub by remember(domain, bins) { mutableStateOf<Int?>(null) }
    var pinned by remember(domain, bins, clearSignal) { mutableStateOf<Int?>(null) }
    val active = scrub ?: pinned
    val upperInclusive = steps != null
    val highlight by rememberUpdatedState(onHighlight)
    LaunchedEffect(active, domain, bins) { highlight(active?.let { bandOf(it, domain, bins, upperInclusive) }) }
    DisposableEffect(Unit) { onDispose { highlight(null) } }
    val haptics = LocalHapticFeedback.current
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bar = ink.copy(alpha = 0.22f)
    val track = ink.copy(alpha = 0.12f)
    val stops = remember(domain, scale) { rampStops(domain, scale) }
    val hereColor = here?.let { Color(scale?.color(it) ?: SequentialRamp.at(0.5)) }
    // Only a step axis marks values past its end. A percentile axis clamps them, as the map does.
    val offScaleMax = if (axis != null) values.filter { it.isFinite() && it > domain.high }.maxOrNull() else null
    val anim = tween<Float>(ANIM_MS)
    val hereX by animateFloatAsState(here?.let { domain.fraction(it) } ?: 0f, anim)
    val minX by animateFloatAsState(hereMin?.let { domain.fraction(it) } ?: 0f, anim)
    val maxX by animateFloatAsState(hereMax?.let { domain.fraction(it) } ?: 0f, anim)
    val showBand = here != null && hereMin != null && hereMax != null
    // Geometry stays on the feed's SI values. Only the labels and the spoken summary convert.
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(property, units)
    val low = if (axis != null) edgeLabel(axis, domain.low, property, units) else formatValue(domain.low, property, units)
    val high = if (axis != null) edgeLabel(axis, domain.high, property, units) else formatValue(domain.high, property, units)
    val offScale = offScaleMax?.let { strings.offScale("${formatValue(it, property, units)} $unit") }
    fun binFrom(i: Int) = domain.low + (domain.high - domain.low) * i / bins
    fun binTo(i: Int) = domain.low + (domain.high - domain.low) * (i + 1) / bins
    // The fullest bar, so the spoken summary says where most areas sit, not only the axis ends.
    val peakBin = counts.indices.maxByOrNull { counts[it] }?.takeIf { counts[it] > 0 }
    val peak = peakBin?.let { i -> strings.spreadPeak(formatValue(binFrom(i), property, units), formatValue(binTo(i), property, units), unit) }
    val spoken = buildString {
        append(strings.spreadSummary(property.shownLabel(), values.size, low, high, unit))
        if (peak != null) append(". ").append(peak)
        if (here != null) {
            append(". ")
            append(strings.rangeSummary(property.shownLabel(), formatValue(here, property, units), hereMin?.let { formatValue(it, property, units) }, hereMax?.let { formatValue(it, property, units) }, unit))
            steps?.stepOf(here)?.let { append(". ").append(strings.stepName(steps, it)) }
        }
        if (offScale != null) append(". ").append(offScale)
    }
    val separator = listSeparator()
    val readout = active?.let { i ->
        val range = "${formatValue(binFrom(i), property, units)} ${strings.to} ${formatValue(binTo(i), property, units)} $unit"
        val step = steps?.stepOf(binMid(i, domain, bins))?.let { strings.stepName(steps, it) }
        spokenList(listOfNotNull(range, strings.areaCount(counts[i]), step), separator)
    }
    // The summary is the chart's name. A touched or pinned bar, which also filters the map and the
    // list, is read as its state.
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = spoken
            if (readout != null) stateDescription = readout
        },
    ) {
        // One label row that never changes height. At rest it names the guideline, while touched it
        // reads the bar under the finger.
        val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        when {
            readout != null -> PositionedLabels(listOf((active + 0.5f) / bins to readout), labelStyle, ink)
            axis != null && guideline != null -> PositionedLabels(listOf(domain.fraction(guideline) to "${guidelineMark(axis, strings)} ${edgeLabel(axis, guideline, property, units)}"), labelStyle, ink)
            else -> PositionedLabels(listOf(0f to " "), labelStyle, ink)
        }
        Spacer(Modifier.height(2.dp))
        val height = BAR_AREA + 6.dp + AXIS + if (showBand) 6.dp + 10.dp else 0.dp
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(bins) {
                // Touch and slide sideways to read each bar. A tap pins a bar, a second tap on it lets go.
                // A mostly vertical move is left to the sheet.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun binAt(x: Float) = (x / size.width * bins).toInt().coerceIn(0, bins - 1)
                    scrub = binAt(down.position.x)
                    var total = Offset.Zero
                    var horizontal = false
                    var tapped = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            tapped = !horizontal
                            break
                        }
                        total += change.positionChange()
                        if (!horizontal) {
                            if (abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x)) break
                            horizontal = abs(total.x) > viewConfiguration.touchSlop
                        }
                        if (horizontal) change.consume()
                        val b = binAt(change.position.x)
                        if (b != scrub) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            scrub = b
                        }
                    }
                    val bin = scrub
                    if (tapped && bin != null) pinned = if (pinned == bin) null else bin
                    scrub = null
                }
            },
        ) {
            val barArea = BAR_AREA.toPx()
            val axisTop = barArea + 6.dp.toPx()
            val axisH = AXIS.toPx()
            val gap = 2.dp.toPx()
            val slot = size.width / bins
            val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
            // Bars. Step bars take their step colour with a faint ink edge, so the light steps still
            // read. Others are thin and grey. The area's own bin is ink (or outlined in ink), so it
            // reads without colour.
            counts.forEachIndexed { i, c ->
                if (c == 0) return@forEachIndexed
                val h = (barArea * c / peak).coerceAtLeast(2.dp.toPx())
                val topLeft = Offset(i * slot + gap / 2, barArea - h)
                val barSize = Size((slot - gap).coerceAtLeast(1f), h)
                val radius = CornerRadius(2.dp.toPx())
                if (steps != null) {
                    val fill = Color(steps.colorOf(binMid(i, domain, bins)) ?: SequentialRamp.at(0.5))
                    drawRoundRect(color = fill, topLeft = topLeft, size = barSize, cornerRadius = radius)
                    val picked = i == (active ?: hereBin)
                    val edge = if (picked) ink else ink.copy(alpha = 0.35f)
                    val stroke = if (picked) 2.dp.toPx() else 1f
                    drawRoundRect(color = edge, topLeft = topLeft, size = barSize, cornerRadius = radius, style = Stroke(stroke))
                } else {
                    drawRoundRect(color = if (i == (active ?: hereBin)) ink else bar, topLeft = topLeft, size = barSize, cornerRadius = radius)
                }
            }
            if (steps != null) {
                // A neutral axis. The guideline crosses bars and axis as a dashed ink line.
                drawRoundRect(color = track, topLeft = Offset(0f, axisTop), size = Size(size.width, axisH), cornerRadius = CornerRadius(axisH / 2))
                if (guideline != null) {
                    val gx = (domain.fraction(guideline) * size.width).coerceIn(1f, size.width - 1f)
                    drawLine(ink, Offset(gx, 0f), Offset(gx, axisTop + axisH), strokeWidth = 1.5.dp.toPx(), pathEffect = GUIDE_DASH)
                }
                if (offScaleMax != null) offScaleCap(Offset(size.width, axisTop + axisH / 2), ink, 5.dp.toPx())
            } else {
                // The map's scale as the axis.
                drawRoundRect(
                    brush = Brush.horizontalGradient(colorStops = stops, startX = 0f, endX = size.width),
                    topLeft = Offset(0f, axisTop),
                    size = Size(size.width, axisH),
                    cornerRadius = CornerRadius(axisH / 2),
                )
            }
            active?.let { i ->
                val sx = (i + 0.5f) * slot
                drawLine(ink, Offset(sx, 0f), Offset(sx, axisTop + axisH), strokeWidth = 1.dp.toPx())
            }
            if (here == null || hereColor == null) return@Canvas
            val x = hereX * size.width
            drawLine(ink, Offset(x, 0f), Offset(x, axisTop + axisH / 2), strokeWidth = 2.dp.toPx())
            if (showBand) {
                val bandTop = axisTop + axisH + 6.dp.toPx() + 3.dp.toPx()
                val left = minX * size.width
                val right = maxX * size.width
                drawRoundRect(
                    color = ink.copy(alpha = 0.5f),
                    topLeft = Offset(left, bandTop),
                    size = Size((right - left).coerceAtLeast(BAND.toPx()), BAND.toPx()),
                    cornerRadius = CornerRadius(BAND.toPx() / 2),
                )
                drawLine(ink, Offset(x, bandTop - 3.dp.toPx()), Offset(x, bandTop + BAND.toPx() + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            marker(Offset(x, axisTop + axisH / 2), hereColor, ink, 7.dp.toPx())
        }
        Spacer(Modifier.height(2.dp))
        // The bars and the axis run low to high left to right in every language, so the edge labels and
        // the off-scale note under the end arrow stay left to right too. Their text still shapes as Arabic.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth()) {
                FitText(low, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                FitText(high, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            }
            if (offScale != null) {
                FitText(offScale, style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * One measurement of the selected area. Label and unit, a range bar on that measurement's own
 * axis (band is this area's min to max, dot is the mean in the map colour) and the mean as text.
 * With a step scale the track runs from zero to an edge with the guideline as a dashed tick, the
 * step name sits under the unit and a value past the track end is marked. Tapping it makes it the hero.
 */
@Composable
internal fun RangeRow(range: MetricRange, strings: DiscoverStrings, onClick: () -> Unit) {
    val p = range.property
    val ink = MaterialTheme.colorScheme.onSurface
    val track = ink.copy(alpha = 0.12f)
    val dot = Color(range.color)
    val anim = tween<Float>(ANIM_MS)
    val meanX by animateFloatAsState(range.domain.fraction(range.mean), anim)
    val minX by animateFloatAsState(range.min?.let { range.domain.fraction(it) } ?: 0f, anim)
    val maxX by animateFloatAsState(range.max?.let { range.domain.fraction(it) } ?: 0f, anim)
    val hasBand = range.min != null && range.max != null
    val guideX = range.guideline?.let { range.domain.fraction(it) }
    val offScale = range.stepAxis && maxOf(range.mean, range.max ?: range.mean) > range.domain.high
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(p, units)
    val stepName = range.steps?.let { steps -> range.step?.let { strings.stepName(steps, it) } }
    val summary = strings.rangeSummary(p.shownLabel(), formatValue(range.mean, p, units), range.min?.let { formatValue(it, p, units) }, range.max?.let { formatValue(it, p, units) }, unit)
    val spoken = if (stepName != null) "$summary. $stepName" else summary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.9f)) {
            FitText(p.shownLabel(), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            FitText(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (stepName != null) FitText(stepName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1.3f).height(24.dp)) {
            val r = 6.dp.toPx()
            val y = size.height / 2
            val w = size.width - 2 * r
            val bh = BAND.toPx()
            drawRoundRect(track, Offset(0f, y - bh / 2), Size(size.width, bh), CornerRadius(bh / 2))
            if (hasBand) {
                val left = r + minX * w
                val right = r + maxX * w
                drawRoundRect(ink.copy(alpha = 0.5f), Offset(left, y - bh / 2), Size((right - left).coerceAtLeast(bh), bh), CornerRadius(bh / 2))
            }
            if (guideX != null) {
                val gx = r + guideX * w
                drawLine(ink, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1.5.dp.toPx(), pathEffect = GUIDE_DASH)
            }
            if (offScale) offScaleCap(Offset(size.width, y), ink, 4.dp.toPx())
            marker(Offset(r + meanX * w, y), dot, ink, r - 1.dp.toPx())
        }
        Text(
            formatValue(range.mean, p, units),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp).padding(start = 8.dp),
        )
    }
}

/**
 * The step swatches of [scale], equal width, lowest first, the edge values under the borders and
 * the guideline marked above its border with an ink tick through the bar. The legend's scale.
 */
@Composable
internal fun StepBar(scale: StepScale, property: FeedProperty, strings: DiscoverStrings, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val units = LocalDiscoverUnits.current
    val n = scale.size
    // The border after the step whose upper bound is the guideline, when the scale has one.
    val guideBorder = scale.guideline?.let { g -> scale.edges.indexOfFirst { it == g }.takeIf { it >= 0 } }?.plus(1)
    Column(modifier) {
        val mark = if (guideBorder != null) listOf(guideBorder.toFloat() / n to guidelineMark(scale, strings)) else listOf(0f to " ")
        PositionedLabels(mark, MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), ink)
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val gap = 2.dp.toPx()
            val barH = 10.dp.toPx()
            val top = (size.height - barH) / 2
            val slot = size.width / n
            scale.colors.forEachIndexed { i, color ->
                val left = i * slot + if (i == 0) 0f else gap / 2
                val right = (i + 1) * slot - if (i == n - 1) 0f else gap / 2
                drawRoundRect(
                    color = Color(color),
                    topLeft = Offset(left, top),
                    size = Size(right - left, barH),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            if (guideBorder != null) drawLine(ink, Offset(guideBorder * slot, 0f), Offset(guideBorder * slot, size.height), strokeWidth = 2.dp.toPx())
        }
        PositionedLabels(scale.edges.mapIndexed { i, e -> (i + 1f) / n to edgeLabel(scale, e, property, units) }, MaterialTheme.typography.labelSmall, muted)
    }
}

/** The mark at a scale's guideline. WHO for the WHO scale, a neutral word for any other. */
internal fun guidelineMark(scale: StepScale, strings: DiscoverStrings): String = if (scale.who != null) strings.whoMark else strings.scaleMark

/**
 * An edge of [scale] as the legend and charts print it. WHO edges are µg/m³ as published and never
 * converted ([formatEdge]). Other edges follow the property's units like its values.
 */
internal fun edgeLabel(scale: StepScale, value: Double, property: FeedProperty, units: UnitFormatter): String =
    if (scale.who != null) formatEdge(value) else formatValue(value, property, units)

/**
 * Texts placed along a width, each centred on its fraction (0 at the left, 1 at the right, as the
 * Canvas axes run in every language) and kept inside the bounds. For edge values and the guideline
 * mark. `place` does not mirror, so the labels stay over their marks in right-to-left layouts.
 */
@Composable
internal fun PositionedLabels(labels: List<Pair<Float, String>>, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Layout(
        content = { labels.forEach { (_, text) -> FitText(text, style = style, color = color, maxLines = 1) } },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val x = (labels[i].first * width - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x, 0)
            }
        }
    }
}

/** A bold value marker. An ink ring around a fill in the map colour, legible on any surface. */
private fun DrawScope.marker(center: Offset, fill: Color, ink: Color, radius: Float) {
    drawCircle(ink, radius + 2.dp.toPx(), center)
    drawCircle(fill, radius, center)
}

/** A small ink arrow at the end of an axis, pointing on, for values past it. [tip] is the point. */
private fun DrawScope.offScaleCap(tip: Offset, ink: Color, size: Float) {
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - size * 1.4f, tip.y - size)
        lineTo(tip.x - size * 1.4f, tip.y + size)
        close()
    }
    drawPath(path, ink)
}

/** Gradient stops that follow [scale] across [domain], clamped past the 5th and 95th percentile like the map. */
private fun rampStops(domain: ChartDomain, scale: CellScale?): Array<Pair<Float, Color>> {
    val n = 16
    return Array(n + 1) { i ->
        val t = i.toFloat() / n
        val v = domain.low + (domain.high - domain.low) * t
        t to Color(scale?.color(v) ?: SequentialRamp.at(t.toDouble()))
    }
}
