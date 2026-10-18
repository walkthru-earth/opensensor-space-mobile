package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Small caps label used above readings and in stat rows. Pass [uppercase] = false when the text
 * carries a unit: `"µm".uppercase()` is `"ΜM"` (Greek capital mu), which is how "(MM)" once
 * appeared on the particles card.
 *
 * Screen readers get the text as written, not the capitals, so VoiceOver and TalkBack do not spell
 * a short capitalised word letter by letter. Pass [heading] = true when the eyebrow titles a card or
 * a section, so it joins the headings list (TalkBack) and the rotor (VoiceOver).
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    uppercase: Boolean = true,
    heading: Boolean = false,
    maxLines: Int = TypographyLimits.EyebrowMaxLines,
) {
    FitText(
        if (uppercase) text.uppercase() else text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier.semantics {
            if (uppercase) contentDescription = text
            if (heading) heading()
        },
        maxLines = maxLines,
    )
}

/** A small round status light. A dot, not a control, so it stays a circle. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Spacer(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** Two-column key/value line for compact stat rows, read as one element ("Battery, 80 %"). */
@Composable
fun StatLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(modifier.fillMaxWidth().cardSemantics(mergeDescendants = true)) {
        val maxValueWidth = maxWidth * 0.55f
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            FitText(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                maxLines = TypographyLimits.StatLabelMaxLines,
            )
            FitText(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                modifier = Modifier.widthIn(max = maxValueWidth),
                textAlign = TextAlign.End,
                maxLines = TypographyLimits.StatValueMaxLines,
            )
        }
    }
}

/** A labelled number with its unit, for the secondary measurements on a metric card, read as one element. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BigValue(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.cardSemantics(mergeDescendants = true)) {
        Eyebrow(label, uppercase = false)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            FitText(value, style = MaterialTheme.typography.displaySmall, modifier = Modifier.alignByBaseline(), maxLines = 1)
            FitText(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline(), maxLines = 1)
        }
    }
}

/**
 * A small tag naming a kind (Bluetooth, Wi-Fi), an icon and a short label on a [FloatingControl.BadgeShape]
 * card. The caller picks the container colours, never the brand blue (that marks selection only).
 */
@Composable
fun TagChip(icon: ImageVector, text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(shape = FloatingControl.BadgeShape, color = container, contentColor = content, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            FitText(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * Sibling sections of one thing (a device's Today, History, Files and Device) as a tab row under
 * the screen's top bar. Selecting the open section again does nothing.
 */
@Composable
fun SectionTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.background,
) {
    val tabs: @Composable () -> Unit = {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selectedIndex,
                onClick = { if (index != selectedIndex) onSelect(index) },
                modifier = Modifier.touchTarget(),
                text = { FitText(label, maxLines = 1) },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Equal tabs while every label fits on one line at its floor size, otherwise the row
        // scrolls so each label keeps the user's size and no word splits ("Histo / ry", #143).
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.titleSmall
        val oneScale = MaterialTheme.typography.oneScaleSize(style)
        val slotPx = with(density) { (maxWidth / labels.size.coerceAtLeast(1) - TabTextPadding * 2).toPx() }
        val fixed = remember(labels, style, oneScale, slotPx, density) { measurer.allFitAtFloor(labels, style, oneScale, slotPx, density) }
        if (fixed) {
            PrimaryTabRow(selectedTabIndex = selectedIndex, containerColor = containerColor, tabs = tabs)
        } else {
            PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, containerColor = containerColor, edgePadding = 0.dp, tabs = tabs)
        }
    }
}

/** Material 3 `Tab` pads its text by 16 dp on each side. */
private val TabTextPadding = 16.dp

/** Wall-clock milliseconds since the epoch, the same clock on Android and iOS. */
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Quantises [nowMs] to [stepMs] buckets anchored at [anchorMs] so a screen reader's spoken
 * description of a live age updates every 10 seconds while the visual clock and stale checks keep
 * their 1 second precision.
 */
fun quantiseSpokenMillis(nowMs: Long, anchorMs: Long, stepMs: Long = 10_000L): Long {
    val safeStep = stepMs.coerceAtLeast(1L)
    val elapsed = (nowMs - anchorMs).coerceAtLeast(0L)
    return anchorMs + (elapsed / safeStep) * safeStep
}

/**
 * A clock that ticks once a second so "updated N s ago" and stale checks stay honest. Read it only
 * inside the leaf that prints the age (see [TickingText]): a read at screen level recomposes the
 * whole screen at 1 Hz.
 */
@Composable
fun rememberNowMillis(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(nowEpochMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = nowEpochMillis()
        }
    }
    return now
}

/**
 * A leaf text that re-renders with the current time at 1 Hz visually, while quantising its
 * accessibility [contentDescription] to 10 second steps so TalkBack and VoiceOver are not
 * interrupted every second.
 */
@Composable
fun TickingText(
    text: (nowMs: Long) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = TypographyLimits.TickingMaxLines,
) {
    val now = rememberNowMillis()
    val anchor = remember { now }
    val spoken = text(quantiseSpokenMillis(now, anchor))
    // FitText reruns its size search on every tick because the text changes. That is a few layouts
    // a second on one short leaf, and the size only moves when the digits change width.
    FitText(
        text(now),
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = color,
        maxLines = maxLines,
    )
}
