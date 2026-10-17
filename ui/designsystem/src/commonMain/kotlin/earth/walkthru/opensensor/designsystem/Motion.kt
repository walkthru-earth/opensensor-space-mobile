package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Composition local indicating whether animations and rapid live tickers should be reduced or
 * skipped for users who prefer reduced motion.
 */
val LocalReduceMotion = compositionLocalOf { false }

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
 * Coarsens a live ticker period from 1 Hz to at least 10 seconds when [reduceMotion] is active, so
 * screen readers and low-vision users are not interrupted by once-per-second recompositions.
 */
fun coarsenTickPeriodMs(periodMs: Long, reduceMotion: Boolean): Long =
    if (reduceMotion) maxOf(periodMs, 10_000L) else periodMs

/** Whether the current composition should skip non-essential transitions and slow live tickers. */
@Composable
fun rememberReduceMotion(): Boolean = shouldReduceMotion(LocalReduceMotion.current)
