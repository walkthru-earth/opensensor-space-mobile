package space.opensensor.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.AccessStatus
import space.opensensor.permissions.IosAuthorization
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.openAppSettings

// When In Use only. Info.plist sets NSLocationDefaultAccuracyReduced, so the prompt offers
// approximate location by default. The fix itself comes from maplibre-compose's provider. iOS
// shows the prompt once. After a denial the next tap offers the app's page in Settings.
@Composable
internal actual fun rememberApproximateLocationAccess(): RuntimeAccess {
    val access = remember { RuntimeAccess(AccessKind.APPROX_LOCATION) }
    val location = remember(access) { CoreLocationAccess(onAnswer = access::onPermissionResult) }
    DisposableEffect(location) { onDispose { location.close() } }
    access.bind(
        AccessHooks.statusBased(
            status = location::status,
            requestPermission = location::request,
            openSettings = ::openAppSettings,
        ),
    )
    return access
}

private class CoreLocationAccess(private val onAnswer: () -> Unit) {
    private var manager: CLLocationManager? = null
    private var waiting = false

    // CLLocationManager holds its delegate weakly, so this object keeps it alive.
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            // Core Location also calls this once on creation, before the user answers.
            if (!waiting || manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            waiting = false
            onAnswer()
        }
    }

    // A fresh manager only reads the status. It shows no prompt.
    fun status(): AccessStatus = IosAuthorization.location((manager ?: CLLocationManager()).authorizationStatus.toLong())

    fun request() {
        val m = manager ?: CLLocationManager().also {
            it.delegate = delegate
            manager = it
        }
        if (m.authorizationStatus != kCLAuthorizationStatusNotDetermined) {
            onAnswer()
            return
        }
        waiting = true
        m.requestWhenInUseAuthorization()
    }

    fun close() {
        manager?.delegate = null
        manager = null
        waiting = false
    }
}
