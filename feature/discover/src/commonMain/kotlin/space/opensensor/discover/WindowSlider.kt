package space.opensensor.discover

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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.publicfeed.FeedWindow
import kotlin.math.roundToInt

// One stop per window, a full touch target each.
private val STOP = FloatingControl.Size

/**
 * The time window as a compact pill on the map's left edge. At rest it shows only the chosen
 * window. Touching it grows it into one snap stop per window, shortest at the top, with a tinted
 * thumb that follows a vertical drag. It stays open until a window is picked, by a tap or the end of
 * a drag, so a slow reach never lands on the map. Tapping the chosen stop folds it without a change.
 * Screen readers get one button whose state is the window name and whose actions pick the other
 * windows, and a double tap opens or folds it.
 * [windows] are the ones the feed on screen offers, so the real feed shows no last hour stop.
 */
@Composable
internal fun WindowSlider(
    window: FeedWindow,
    windows: List<FeedWindow>,
    strings: DiscoverStrings,
    onWindow: (FeedWindow) -> Unit,
    modifier: Modifier = Modifier,
    daily: Boolean = false,
) {
    // The stop of the chosen window. A window the feed does not offer is never chosen for long.
    val index = windows.indexOf(window).coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current
    val stopPx = with(LocalDensity.current) { STOP.toPx() }
    val current by rememberUpdatedState(window)
    var expanded by remember { mutableStateOf(false) }
    // While dragging, the thumb follows the finger. Otherwise it springs to the chosen stop.
    var drag by remember { mutableStateOf<Float?>(null) }
    val springy = spring<Float>(dampingRatio = 0.7f)
    val settled by animateFloatAsState(index.toFloat(), springy)
    val thumb = drag ?: settled
    val height by animateDpAsState(if (expanded) STOP * windows.size else STOP, spring(dampingRatio = 0.8f))
    // Collapsed, the column scrolls so the chosen stop sits inside the pill.
    val shift by animateFloatAsState(if (expanded) 0f else index.toFloat(), spring(dampingRatio = 0.8f))

    // The window on screen is always reported too, so choosing a coerced one (24 hours standing in
    // for a saved last hour in Real) saves it. The controller ignores a repeat.
    fun pick(w: FeedWindow) {
        if (w != current) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onWindow(w)
        expanded = false
    }

    val title = strings.timeWindow
    val state = windowLabel(window, strings, daily)
    val actions = windows.filter { it != window }.map { w -> CustomAccessibilityAction(windowLabel(w, strings, daily)) { pick(w); true } }
    FloatingSurface(
        modifier = modifier.width(STOP).height(height).clearAndSetSemantics {
            contentDescription = title
            stateDescription = state
            role = Role.Button
            customActions = actions
            onClick {
                expanded = !expanded
                true
            }
        },
    ) {
        Box(
            Modifier.clipToBounds()
                .pointerInput(windows) {
                    detectTapGestures { at ->
                        if (!expanded) {
                            expanded = true
                        } else {
                            pick(windows[(at.y / stopPx).toInt().coerceIn(0, windows.lastIndex)])
                        }
                    }
                }
                .pointerInput(windows) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            expanded = true
                            drag = windows.indexOf(current).coerceAtLeast(0).toFloat()
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
                    windows.forEachIndexed { i, w ->
                        val selected = if (drag != null) i == thumb.roundToInt() else w == window
                        Box(Modifier.size(STOP), contentAlignment = Alignment.Center) {
                            FitText(
                                strings.windowShort(w, daily),
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

internal fun DiscoverStrings.windowShort(w: FeedWindow, daily: Boolean = false): String = when (w) {
    FeedWindow.LAST_HOUR -> windowShortHour
    FeedWindow.DAY -> if (daily) windowShortLatestDay else windowShortDay
    FeedWindow.WEEK -> windowShortWeek
}
