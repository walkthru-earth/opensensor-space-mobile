package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Composition local indicating whether non-essential animations should be skipped for users who
 * prefer reduced motion. Provided at the app root from [rememberSystemReduceMotion].
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * Reads the operating system's reduce-motion setting (Android `ANIMATOR_DURATION_SCALE` and
 * `ValueAnimator.areAnimatorsEnabled()`, iOS `UIAccessibility.isReduceMotionEnabled` updated via
 * `UIAccessibilityReduceMotionStatusDidChangeNotification`).
 */
@Composable
expect fun rememberSystemReduceMotion(): Boolean

/**
 * Pure decision helper for reduce-motion preferences. Returns true when [reduceMotion] is enabled
 * or when the animation [motionScaleFactor] is zero or negative.
 */
fun shouldReduceMotion(reduceMotion: Boolean = false, motionScaleFactor: Float = 1f): Boolean =
    reduceMotion || motionScaleFactor <= 0f

/**
 * Returns the animation duration in milliseconds, collapsing to zero when [reduceMotion] is true.
 */
fun reduceMotionDurationMillis(defaultMs: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else defaultMs.coerceAtLeast(0)

/**
 * Whether the current composition should skip non-essential animations, combining
 * [LocalReduceMotion] and the live platform setting from [rememberSystemReduceMotion].
 */
@Composable
fun rememberReduceMotion(): Boolean =
    shouldReduceMotion(LocalReduceMotion.current || rememberSystemReduceMotion())
