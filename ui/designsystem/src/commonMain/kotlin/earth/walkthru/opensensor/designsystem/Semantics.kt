package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimum touch target size (48 dp) across Android and iOS per WCAG 2.5.8 and platform guidelines. */
val MinTouchTarget: Dp = 48.dp

/** Minimum gap (8 dp) between adjacent interactive controls to prevent accidental taps. */
val MinTouchSpacing: Dp = 8.dp

/**
 * Enforces a minimum interactive component size and a minimum layout box of [minWidth] by
 * [minHeight] (both defaulting to [MinTouchTarget], 48 dp).
 */
fun Modifier.touchTarget(
    minWidth: Dp = MinTouchTarget,
    minHeight: Dp = MinTouchTarget,
): Modifier = this
    .minimumInteractiveComponentSize()
    .sizeIn(minWidth = minWidth, minHeight = minHeight)

/** Marks a title or section header as an accessibility heading for TalkBack and VoiceOver rotor navigation. */
fun Modifier.headingSemantics(): Modifier = this.semantics { heading() }

/**
 * Merges a card or stat container into a single screen reader focus stop, with optional
 * [contentDescription] and [stateDescription].
 */
fun Modifier.cardSemantics(
    mergeDescendants: Boolean = true,
    contentDescription: String? = null,
    stateDescription: String? = null,
): Modifier = this.semantics(mergeDescendants = mergeDescendants) {
    if (contentDescription != null) this.contentDescription = contentDescription
    if (stateDescription != null) this.stateDescription = stateDescription
}

/**
 * Formats a labelled reading with its optional [unit] and [state] (such as a mood word) in spoken
 * order (`"PM2.5, 12 µg/m³, Fair"`).
 */
fun formatValueDescription(
    label: String,
    value: String,
    unit: String? = null,
    state: String? = null,
    separator: String = ",",
): String {
    val reading = if (unit.isNullOrBlank()) value else "$value $unit"
    return spokenList(listOfNotNull(label.takeIf { it.isNotBlank() }, reading, state?.takeIf { it.isNotBlank() }), separator)
}

/**
 * Merges a reading element and sets its spoken [formatValueDescription] for TalkBack and VoiceOver.
 */
fun Modifier.valueSemantics(
    label: String,
    value: String,
    unit: String? = null,
    state: String? = null,
    separator: String = ",",
): Modifier = this.semantics(mergeDescendants = true) {
    contentDescription = formatValueDescription(label, value, unit, state, separator)
}

/**
 * Makes a labelled switch row a single 48 dp toggleable accessibility node with [Role.Switch].
 */
fun Modifier.switchRowSemantics(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
): Modifier = this
    .touchTarget()
    .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange,
    )
