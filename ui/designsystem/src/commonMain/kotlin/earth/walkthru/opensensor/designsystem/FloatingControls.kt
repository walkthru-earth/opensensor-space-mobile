package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Floating controls, the one layer of chrome that sits over content (the map, a photo, a chart).
 * The rules, shared by Android and iOS (docs/screens.md, "Floating controls").
 *
 * - One layer. Controls float above content, never on top of each other, and content itself is
 *   never styled as a control.
 * - Compact at rest. A control shows only its current state and grows into its options while it
 *   is in use, then folds back.
 * - Curved cards, not circles. Every control uses a rounded card shape. Anything nested inside is
 *   concentric, its radius is the outer radius minus the inset.
 * - Tint means something. Brand colour marks the selected or primary thing only. The rest is
 *   neutral surface and text.
 * - Readable over anything. A near opaque surface, a hairline border and a soft shadow. Touch
 *   targets are at least [FloatingControl.Size].
 */
object FloatingControl {
    /** The smallest side of a control, and its touch target. */
    val Size: Dp = 44.dp

    /** The corner radius of every floating control. */
    val Radius: Dp = 16.dp

    /** The gap between a nested element (a thumb, a selected segment) and its control's edge. */
    val Inset: Dp = 4.dp

    /** The gap between a control and the screen edge, and between neighbouring controls. */
    val Margin: Dp = 12.dp

    val Shape: Shape = RoundedCornerShape(Radius)

    /** Compact controls under 40dp tall (chips, small pills) keep a card curve, never a full circle. */
    val SmallShape: Shape = RoundedCornerShape(12.dp)

    /**
     * The selected or primary tint, the website's Signal blue `main` with its `ink` text. Both are
     * the same in the light and dark palettes, so a selection looks identical in either theme.
     */
    val Selected: Color = BrandTokens.Light.main
    val OnSelected: Color = BrandTokens.Light.ink

    /** The shape of something nested [inset] inside a control, so the two curves stay parallel. */
    fun concentric(inset: Dp = Inset): Shape = RoundedCornerShape((Radius - inset).coerceAtLeast(0.dp))
}

/** The surface every floating control sits on. */
@Composable
fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = FloatingControl.Shape,
    color: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        content = content,
    )
}

/** A square floating button for one icon, [FloatingControl.Size] on each side. */
@Composable
fun FloatingIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(FloatingControl.Size).semantics {
            this.contentDescription = contentDescription
            role = Role.Button
        },
        shape = FloatingControl.Shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
