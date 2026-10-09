package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
) {
    Text(
        if (uppercase) text.uppercase() else text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier.semantics {
            if (uppercase) contentDescription = text
            if (heading) heading()
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** A labelled number with its unit, for the secondary measurements on a metric card, read as one element. */
@Composable
fun BigValue(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Eyebrow(label, uppercase = false)
        Row {
            Text(value, style = MaterialTheme.typography.displaySmall, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
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
            Text(text, style = MaterialTheme.typography.labelSmall)
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
    PrimaryTabRow(selectedTabIndex = selectedIndex, containerColor = containerColor) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selectedIndex,
                onClick = { if (index != selectedIndex) onSelect(index) },
                text = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Wall-clock milliseconds since the epoch, the same clock on Android and iOS. */
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * A clock that ticks once a second so "updated N s ago" stays honest. Read it only inside the leaf
 * that prints the age (see [TickingText]): a read at screen level recomposes the whole screen at
 * 1 Hz.
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

/** A leaf text that re-renders once a second with the current time, so its parent does not. */
@Composable
fun TickingText(
    text: (nowMs: Long) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    val now = rememberNowMillis()
    Text(text(now), modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
