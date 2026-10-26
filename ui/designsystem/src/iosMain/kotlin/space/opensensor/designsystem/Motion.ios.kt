package space.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

/**
 * Seam for reading iOS `UIAccessibility.isReduceMotionEnabled`, replaceable in simulator tests so
 * notification updates can be verified deterministically.
 */
internal var iosReduceMotionReader: () -> Boolean = ::UIAccessibilityIsReduceMotionEnabled

/** Whether iOS Reduce Motion is currently enabled in Accessibility settings. */
fun isSystemReduceMotionEnabled(): Boolean = iosReduceMotionReader()

@Composable
actual fun rememberSystemReduceMotion(): Boolean {
    var reduceMotion by remember { mutableStateOf(isSystemReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        reduceMotion = isSystemReduceMotionEnabled()
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            reduceMotion = isSystemReduceMotionEnabled()
        }
        onDispose {
            center.removeObserver(observer)
        }
    }
    return reduceMotion
}
