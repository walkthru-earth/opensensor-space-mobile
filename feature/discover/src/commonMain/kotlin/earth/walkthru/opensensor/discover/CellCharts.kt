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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.PercentileScale
import earth.walkthru.aqsync.publicfeed.SequentialRamp

/*
 * Sheet charts, drawn with Canvas in common code so Android and iOS match. The feed has no hourly
 * history per cell, so nothing here is a time series. Each chart shows one window's statistics.
 * Marks are ink (onSurface) and grey, the only hue is the map's viridis scale, and every chart
 * carries a spoken summary of its values. Missing values are left out, never drawn as zero.
 */

private val BAR_AREA = 56.dp
private val AXIS = 8.dp
private val BAND = 4.dp
private const val ANIM_MS = 350

internal fun windowLabel(window: FeedWindow, strings: DiscoverStrings): String = when (window) {
    FeedWindow.LAST_HOUR -> strings.windowLastHour
    FeedWindow.DAY -> strings.window24Hours
    FeedWindow.WEEK -> strings.window7Days
}

/**
 * Where one area sits among the areas in view. Grey bars count the areas by mean, the axis under
 * them is the map's colour scale, and [here] (when set) is a bold marker with its min to max band
 * below the axis. Without [here] it is the distribution alone, for the list of areas.
 */
@Composable
internal fun SpreadChart(
    values: List<Double>,
    property: FeedProperty,
    scale: PercentileScale?,
    strings: DiscoverStrings,
    modifier: Modifier = Modifier,
    here: Double? = null,
    hereMin: Double? = null,
    hereMax: Double? = null,
) {
    val domain = remember(values) { ChartDomain.of(values) } ?: return
    val bins = binCount(values.size)
    val counts = remember(values, domain, bins) { histogram(values, domain, bins) }
    val hereBin = here?.let { binOf(it, domain, bins) }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bar = ink.copy(alpha = 0.22f)
    val stops = remember(domain, scale) { rampStops(domain, scale) }
    val hereColor = here?.let { Color(scale?.color(it) ?: SequentialRamp.at(0.5)) }
    val anim = tween<Float>(ANIM_MS)
    val hereX by animateFloatAsState(here?.let { domain.fraction(it) } ?: 0f, anim)
    val minX by animateFloatAsState(hereMin?.let { domain.fraction(it) } ?: 0f, anim)
    val maxX by animateFloatAsState(hereMax?.let { domain.fraction(it) } ?: 0f, anim)
    val showBand = here != null && hereMin != null && hereMax != null
    // Geometry stays on the feed's SI values. Only the labels and the spoken summary convert.
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(property, units)
    val low = formatValue(domain.low, property, units)
    val high = formatValue(domain.high, property, units)
    val spoken = buildString {
        append(strings.spreadSummary(property.label, values.size, low, high, unit))
        if (here != null) {
            append(". ")
            append(strings.rangeSummary(property.label, formatValue(here, property, units), hereMin?.let { formatValue(it, property, units) }, hereMax?.let { formatValue(it, property, units) }, unit))
        }
    }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        val height = BAR_AREA + 6.dp + AXIS + if (showBand) 6.dp + 10.dp else 0.dp
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val barArea = BAR_AREA.toPx()
            val axisTop = barArea + 6.dp.toPx()
            val axisH = AXIS.toPx()
            val gap = 2.dp.toPx()
            val slot = size.width / bins
            val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
            // Bars, thin and grey. The area's own bin is ink, so it reads without colour.
            counts.forEachIndexed { i, c ->
                if (c == 0) return@forEachIndexed
                val h = (barArea * c / peak).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (i == hereBin) ink else bar,
                    topLeft = Offset(i * slot + gap / 2, barArea - h),
                    size = Size((slot - gap).coerceAtLeast(1f), h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            // The map's scale as the axis.
            drawRoundRect(
                brush = Brush.horizontalGradient(colorStops = stops, startX = 0f, endX = size.width),
                topLeft = Offset(0f, axisTop),
                size = Size(size.width, axisH),
                cornerRadius = CornerRadius(axisH / 2),
            )
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
    }
}

/**
 * One measurement of the selected area. Label and unit, a range bar on that measurement's own
 * axis (track is the 5th to 95th percentile in view, band is this area's min to max, dot is the mean in the map
 * colour) and the mean as text. Tapping it makes it the hero.
 */
@Composable
internal fun RangeRow(range: MetricRange, strings: DiscoverStrings, onClick: () -> Unit) {
    val p = range.property
    val ink = MaterialTheme.colorScheme.onSurface
    val track = ink.copy(alpha = 0.12f)
    val dot = Color(SequentialRamp.at(range.colorFraction))
    val anim = tween<Float>(ANIM_MS)
    val meanX by animateFloatAsState(range.domain.fraction(range.mean), anim)
    val minX by animateFloatAsState(range.min?.let { range.domain.fraction(it) } ?: 0f, anim)
    val maxX by animateFloatAsState(range.max?.let { range.domain.fraction(it) } ?: 0f, anim)
    val hasBand = range.min != null && range.max != null
    val units = LocalDiscoverUnits.current
    val unit = unitLabel(p, units)
    val spoken = strings.rangeSummary(p.label, formatValue(range.mean, p, units), range.min?.let { formatValue(it, p, units) }, range.max?.let { formatValue(it, p, units) }, unit)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.9f)) {
            Text(p.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

/** A bold value marker. An ink ring around a fill in the map colour, legible on any surface. */
private fun DrawScope.marker(center: Offset, fill: Color, ink: Color, radius: Float) {
    drawCircle(ink, radius + 2.dp.toPx(), center)
    drawCircle(fill, radius, center)
}

/** Gradient stops that follow [scale] across [domain], clamped past the 5th and 95th percentile like the map. */
private fun rampStops(domain: ChartDomain, scale: PercentileScale?): Array<Pair<Float, Color>> {
    val n = 16
    return Array(n + 1) { i ->
        val t = i.toFloat() / n
        val v = domain.low + (domain.high - domain.low) * t
        t to Color(scale?.color(v) ?: SequentialRamp.at(t.toDouble()))
    }
}
