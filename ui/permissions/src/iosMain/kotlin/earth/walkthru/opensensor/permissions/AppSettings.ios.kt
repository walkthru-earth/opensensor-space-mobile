package earth.walkthru.opensensor.permissions

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** Opens this app's page in the iOS Settings app, where its permissions are. */
fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

@Composable
actual fun rememberOpenAppSettings(): () -> Unit = ::openAppSettings
