package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIColor
import platform.UIKit.UIScene
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UISceneWillDeactivateNotification
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.systemBackgroundColor

private val guardCount = CaptureGuardCount()
private var observers: List<Any> = emptyList()
private var screenCaptured by mutableStateOf(false)

/** Covers added before the app switcher snapshot, removed when the scene is active again. */
private val covers = mutableListOf<UIView>()

private fun isScreenCaptured(): Boolean = UIScreen.mainScreen.isCaptured()

@OptIn(ExperimentalForeignApi::class)
private fun coverScene(scene: UIScene?) {
    val windowScene = scene as? UIWindowScene ?: return
    if (covers.isNotEmpty()) return
    windowScene.windows.filterIsInstance<UIWindow>().forEach { window ->
        val cover = UIView(frame = window.bounds)
        cover.backgroundColor = UIColor.systemBackgroundColor
        cover.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        window.addSubview(cover)
        covers += cover
    }
}

private fun uncover() {
    covers.forEach { it.removeFromSuperview() }
    covers.clear()
}

private fun startObserving() {
    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    screenCaptured = isScreenCaptured()
    observers = listOf(
        center.addObserverForName(UIScreenCapturedDidChangeNotification, null, queue) { _: NSNotification? ->
            screenCaptured = isScreenCaptured()
        },
        center.addObserverForName(UISceneWillDeactivateNotification, null, queue) { note: NSNotification? ->
            coverScene(note?.`object` as? UIScene)
        },
        center.addObserverForName(UISceneDidActivateNotification, null, queue) { _: NSNotification? ->
            uncover()
        },
    )
}

private fun stopObserving() {
    val center = NSNotificationCenter.defaultCenter
    observers.forEach { center.removeObserver(it) }
    observers = emptyList()
    uncover()
    screenCaptured = false
}

@Composable
actual fun rememberCredentialCaptureGuard(): Boolean {
    DisposableEffect(Unit) {
        if (guardCount.acquire()) startObserving()
        onDispose { if (guardCount.release()) stopObserving() }
    }
    return screenCaptured
}
