package earth.walkthru.opensensor.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject

// When In Use only. Info.plist sets NSLocationDefaultAccuracyReduced, so the prompt offers
// approximate location by default. The fix itself comes from maplibre-compose's provider.
@Composable
internal actual fun rememberApproximateLocationPermission(): ApproximateLocationPermission {
    val permission = remember { CoreLocationPermission() }
    DisposableEffect(permission) { onDispose { permission.close() } }
    return permission
}

private class CoreLocationPermission : ApproximateLocationPermission {
    private var manager: CLLocationManager? = null
    private var pending: ((Boolean) -> Unit)? = null

    // CLLocationManager holds its delegate weakly, so this object keeps it alive.
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            // Core Location also calls this once on creation, before the user answers.
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            pending?.invoke(manager.isAuthorized())
            pending = null
        }
    }

    // A fresh manager only reads the status. It shows no prompt.
    override val granted: Boolean get() = CLLocationManager().isAuthorized()

    override fun request(onResult: (Boolean) -> Unit) {
        val m = manager ?: CLLocationManager().also {
            it.delegate = delegate
            manager = it
        }
        if (m.authorizationStatus != kCLAuthorizationStatusNotDetermined) {
            // Denied or restricted. iOS shows the prompt once, so a later tap does nothing.
            onResult(m.isAuthorized())
            return
        }
        pending = onResult
        m.requestWhenInUseAuthorization()
    }

    fun close() {
        manager?.delegate = null
        manager = null
        pending = null
    }
}

private fun CLLocationManager.isAuthorized(): Boolean =
    authorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse || authorizationStatus == kCLAuthorizationStatusAuthorizedAlways
