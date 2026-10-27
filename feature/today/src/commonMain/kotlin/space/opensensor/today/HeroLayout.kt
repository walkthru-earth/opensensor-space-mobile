package space.opensensor.today

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The hero's secondary text (title, unit, freshness) is the card ink at this opacity. */
internal const val HERO_QUIET_ALPHA = 0.85f

/** From this font scale the peacock sits above the number, so the value keeps its full width. */
internal const val HERO_STACK_FONT_SCALE = 1.3f

/** Below this card width the peacock sits above the number at any font scale. */
internal val HERO_STACK_WIDTH: Dp = 300.dp

/** The hero's quiet ink, already laid over [card] so its contrast can be measured. */
internal fun heroQuiet(onCard: Color, card: Color): Color = onCard.copy(alpha = HERO_QUIET_ALPHA).compositeOver(card)

/** Whether the hero stacks the peacock above the number, for large text or a narrow card. */
internal fun heroStacked(maxWidth: Dp, fontScale: Float): Boolean = fontScale >= HERO_STACK_FONT_SCALE || maxWidth < HERO_STACK_WIDTH
