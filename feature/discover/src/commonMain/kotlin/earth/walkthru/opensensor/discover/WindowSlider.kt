package earth.walkthru.opensensor.discover

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.FloatingSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val STOP = 44.dp
private const val COLLAPSE_MS = 2200L

/**
 * The time window as a compact pill on the map's left edge. At rest it shows only the chosen
 * window. Touching it grows it into one snap stop per window, shortest at the top, with a tinted
 * thumb that follows a vertical drag. It folds back shortly after the last touch. Screen readers
 * get one control whose state is the window name and whose actions pick the other windows.
 */
@Composable
internal fun WindowSlider(window: FeedWindow, strings: DiscoverStrings, onWindow: (FeedWindow) -> Unit, modifier: Modifier = Modifier) {
    val windows = FeedWindow.entries
    val haptics = LocalHapticFeedback.current
    val stopPx = with(LocalDensity.current) { STOP.toPx() }
    val current by rememberUpdatedState(window)
    var expanded by remember { mutableStateOf(false) }
    // While dragging, the thumb follows the finger. Otherwise it springs to the chosen stop.
    var drag by remember { mutableStateOf<Float?>(null) }
    val springy = spring<Float>(dampingRatio = 0.7f)
    val settled by animateFloatAsState(window.ordinal.toFloat(), springy)
    val thumb = drag ?: settled
    val height by animateDpAsState(if (expanded) STOP * windows.size else STOP, spring(dampingRatio = 0.8f))
    // Collapsed, the column scrolls so the chosen stop sits inside the pill.
    val shift by animateFloatAsState(if (expanded) 0f else window.ordinal.toFloat(), spring(dampingRatio = 0.8f))

    LaunchedEffect(expanded, drag, window) {
        if (expanded && drag == null) {
            delay(COLLAPSE_MS)
            expanded = false
        }
    }

    fun pick(w: FeedWindow) {
        if (w != current) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onWindow(w)
        }
    }

    val title = strings.timeWindow
    val state = windowLabel(window, strings)
    val actions = windows.filter { it != window }.map { w -> CustomAccessibilityAction(windowLabel(w, strings)) { onWindow(w); true } }
    FloatingSurface(
        modifier = modifier.width(STOP).height(height).clearAndSetSemantics {
            contentDescription = title
            stateDescription = state
            customActions = actions
        },
    ) {
        Box(
            Modifier.clipToBounds()
                .pointerInput(windows.size) {
                    detectTapGestures { at ->
                        if (!expanded) {
                            expanded = true
                        } else {
                            pick(windows[(at.y / stopPx).toInt().coerceIn(0, windows.lastIndex)])
                        }
                    }
                }
                .pointerInput(windows.size) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            expanded = true
                            drag = current.ordinal.toFloat()
                        },
                        onDragEnd = {
                            val stop = (drag ?: 0f).roundToInt().coerceIn(0, windows.lastIndex)
                            drag = null
                            pick(windows[stop])
                        },
                        onDragCancel = { drag = null },
                    ) { change, dy ->
                        change.consume()
                        val before = (drag ?: 0f).roundToInt()
                        val next = ((drag ?: 0f) + dy / stopPx).coerceIn(0f, windows.lastIndex.toFloat())
                        if (next.roundToInt() != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        drag = next
                    }
                },
        ) {
            Box(Modifier.offset { IntOffset(0, (-shift * stopPx).roundToInt()) }.wrapContentHeight(unbounded = true, align = Alignment.Top)) {
                // The only tint is the thumb, so the chosen window is the one thing that stands out. It
                // sits one inset inside the pill with a concentric corner.
                Box(
                    Modifier.offset { IntOffset(0, (thumb * stopPx).roundToInt()) }
                        .padding(FloatingControl.Inset)
                        .size(STOP - FloatingControl.Inset * 2)
                        .background(FloatingControl.Selected, FloatingControl.concentric()),
                )
                Column {
                    windows.forEach { w ->
                        val selected = if (drag != null) w.ordinal == thumb.roundToInt() else w == window
                        Box(Modifier.size(STOP), contentAlignment = Alignment.Center) {
                            Text(
                                strings.windowShort(w),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun DiscoverStrings.windowShort(w: FeedWindow): String = when (w) {
    FeedWindow.LAST_HOUR -> windowShortHour
    FeedWindow.DAY -> windowShortDay
    FeedWindow.WEEK -> windowShortWeek
}
