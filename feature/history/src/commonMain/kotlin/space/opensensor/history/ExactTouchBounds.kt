package space.opensensor.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize

/**
 * Lays [content] out with no minimum touch target, so a screen reader node keeps the bounds it is
 * given (#141). Chart bars and calendar days are often narrower than 48 dp. Grown to 48 dp, each
 * node overlaps the next one, which then covers its right half, and every node ends up a bar to the
 * left of its mark. These nodes are semantics only, a finger still lands on the canvas.
 */
@Composable
internal fun ExactTouchBounds(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val exact = object : ViewConfiguration by base {
        override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
    }
    CompositionLocalProvider(LocalViewConfiguration provides exact, content = content)
}
