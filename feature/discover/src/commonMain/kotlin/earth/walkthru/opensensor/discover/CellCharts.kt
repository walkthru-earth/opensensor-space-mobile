package earth.walkthru.opensensor.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.publicfeed.CellScale
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.SequentialRamp
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

internal fun windowLabel(window: FeedWindow, strings: DiscoverStrings): String = when (window) {
    FeedWindow.LAST_HOUR -> strings.windowLastHour
    FeedWindow.DAY -> strings.window24Hours
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
) {
    val domain = remember(values, property.id) { ChartDomain.forProperty(property.id, values) } ?: return
    val health = (scale as? CellScale.Health)?.scale
    val bins = remember(domain, property.id, values.size) { chartBins(property.id, domain, values.size) }
    val counts = remember(values, domain, bins, health) { histogram(values, domain, bins, upperInclusive = health != null) }
    val hereBin = here?.let { binOf(it, domain, bins, upperInclusive = health != null) }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bar = ink.copy(alpha = 0.22f)
    val track = ink.copy(alpha = 0.12f)
    val stops = remember(domain, scale) { rampStops(domain, scale) }
    val hereColor = here?.let { Color(scale?.color(it) ?: SequentialRamp.at(0.5)) }
    // Only a WHO axis marks values past its end. A percentile axis clamps them, as the map does.
    val offScaleMax = if (health != null) values.filter { it.isFinite() && it > domain.high }.maxOrNull() else null
    val anim = tween<Float>(ANIM_MS)
    val hereX by animateFloatAsState(here?.let { domain.fraction(it) } ?: 0f, anim)
    val minX by animateFloatAsState(hereMin?.let { domain.fraction(it) } ?: 0f, anim)
    val maxX by animateFloatAsState(hereMax?.let { domain.fraction(it) } ?: 0f, anim)
    val showBand = here != null && hereMin != null && hereMax != null
    // Geometry stays on the feed's SI values. Only the labels and the spoken summary convert.
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(property, units)
    val low = if (health != null) formatEdge(domain.low) else formatValue(domain.low, property, units)
    val high = if (health != null) formatEdge(domain.high) else formatValue(domain.high, property, units)
    val offScale = offScaleMax?.let { strings.offScale("${formatValue(it, property, units)} $unit") }
    val spoken = buildString {
        append(strings.spreadSummary(property.label, values.size, low, high, unit))
        if (here != null) {
            append(". ")
            append(strings.rangeSummary(property.label, formatValue(here, property, units), hereMin?.let { formatValue(it, property, units) }, hereMax?.let { formatValue(it, property, units) }, unit))
            health?.stepOf(here)?.let { append(". ").append(strings.stepName(it)) }
        }
        if (offScale != null) append(". ").append(offScale)
    }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        if (health != null) {
            PositionedLabels(
                listOf(domain.fraction(health.guideline) to "${strings.whoMark} ${formatEdge(health.guideline)}"),
                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                ink,
            )
            Spacer(Modifier.height(2.dp))
        }
        val height = BAR_AREA + 6.dp + AXIS + if (showBand) 6.dp + 10.dp else 0.dp
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barArea = BAR_AREA.toPx()
            val axisTop = barArea + 6.dp.toPx()
            val axisH = AXIS.toPx()
            val gap = 2.dp.toPx()
            val slot = size.width / bins
            val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
            // Bars. WHO bars take their step colour with a faint ink edge, so the light steps still
            // read. Others are thin and grey. The area's own bin is ink (or outlined in ink), so it
            // reads without colour.
            counts.forEachIndexed { i, c ->
                if (c == 0) return@forEachIndexed
                val h = (barArea * c / peak).coerceAtLeast(2.dp.toPx())
                val topLeft = Offset(i * slot + gap / 2, barArea - h)
                val barSize = Size((slot - gap).coerceAtLeast(1f), h)
                val radius = CornerRadius(2.dp.toPx())
                if (health != null) {
                    val fill = Color(health.colorOf(binMid(i, domain, bins)) ?: SequentialRamp.at(0.5))
                    drawRoundRect(color = fill, topLeft = topLeft, size = barSize, cornerRadius = radius)
                    val edge = if (i == hereBin) ink else ink.copy(alpha = 0.35f)
                    val stroke = if (i == hereBin) 2.dp.toPx() else 1f
                    drawRoundRect(color = edge, topLeft = topLeft, size = barSize, cornerRadius = radius, style = Stroke(stroke))
                } else {
                    drawRoundRect(color = if (i == hereBin) ink else bar, topLeft = topLeft, size = barSize, cornerRadius = radius)
                }
            }
            if (health != null) {
                // A neutral axis. The guideline crosses bars and axis as a dashed ink line.
                drawRoundRect(color = track, topLeft = Offset(0f, axisTop), size = Size(size.width, axisH), cornerRadius = CornerRadius(axisH / 2))
                val gx = (domain.fraction(health.guideline) * size.width).coerceIn(1f, size.width - 1f)
                drawLine(ink, Offset(gx, 0f), Offset(gx, axisTop + axisH), strokeWidth = 1.5.dp.toPx(), pathEffect = GUIDE_DASH)
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
        Row(Modifier.fillMaxWidth()) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text(high, style = MaterialTheme.typography.labelSmall, color = muted)
        }
        if (offScale != null) {
            Text(offScale, style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * One measurement of the selected area. Label and unit, a range bar on that measurement's own
 * axis (band is this area's min to max, dot is the mean in the map colour) and the mean as text.
 * For PM the track runs from zero to a WHO edge with the guideline as a dashed tick, the step name
 * sits under the unit and a value past the track end is marked. Tapping it makes it the hero.
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
    val offScale = range.guideline != null && maxOf(range.mean, range.max ?: range.mean) > range.domain.high
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(p, units)
    val stepName = range.step?.let { strings.stepName(it) }
    val summary = strings.rangeSummary(p.label, formatValue(range.mean, p, units), range.min?.let { formatValue(it, p, units) }, range.max?.let { formatValue(it, p, units) }, unit)
    val spoken = if (stepName != null) "$summary. $stepName" else summary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.9f)) {
            Text(p.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (stepName != null) Text(stepName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
 * Six WHO step swatches, equal width, cleanest first, the edge values under the borders and the
 * guideline marked above its border with an ink tick through the bar. The legend's scale.
 */
@Composable
internal fun WhoStepBar(health: HealthScale, strings: DiscoverStrings, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val steps = WhoStep.entries
    val n = steps.size
    Column(modifier) {
        PositionedLabels(listOf(1f / n to strings.whoMark), MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), ink)
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val gap = 2.dp.toPx()
            val barH = 10.dp.toPx()
            val top = (size.height - barH) / 2
            val slot = size.width / n
            steps.forEachIndexed { i, step ->
                val left = i * slot + if (i == 0) 0f else gap / 2
                val right = (i + 1) * slot - if (i == n - 1) 0f else gap / 2
                drawRoundRect(
                    color = Color(step.color),
                    topLeft = Offset(left, top),
                    size = Size(right - left, barH),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            // The guideline border.
            drawLine(ink, Offset(slot, 0f), Offset(slot, size.height), strokeWidth = 2.dp.toPx())
        }
        PositionedLabels(health.edges.mapIndexed { i, e -> (i + 1f) / n to formatEdge(e) }, MaterialTheme.typography.labelSmall, muted)
    }
}

/**
 * Texts placed along a width, each centred on its fraction (0 at the start, 1 at the end) and kept
 * inside the bounds. For edge values and the guideline mark.
 */
@Composable
internal fun PositionedLabels(labels: List<Pair<Float, String>>, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Layout(
        content = { labels.forEach { (_, text) -> Text(text, style = style, color = color, maxLines = 1) } },
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
