@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.IosAuthorization
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.openAppSettings
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.platformH3Grid

@Composable
internal actual fun rememberDeviceLocationRequest(): DeviceLocationRequest {
    val access = remember { RuntimeAccess(AccessKind.APPROX_LOCATION) }
    val location = remember { CellLocation(access::onPermissionResult) }
    DisposableEffect(location) { onDispose { location.close() } }
    access.bind(
        AccessHooks.statusBased(
            status = { IosAuthorization.location(location.manager.authorizationStatus.toLong()) },
            requestPermission = location::requestPermission,
            openSettings = ::openAppSettings,
        )
    )
    return remember(location, access) { DeviceLocationRequest(access) { resolution -> if (access.ready) location.cell(resolution) else null } }
}

/** Manager and strong delegate live only while the location dialog is open. No background updates. */
private class CellLocation(private val onPermissionResult: () -> Unit) {
    val manager = CLLocationManager()
    private var awaitingPermission = false
    private val pending = PendingFix(FIX_TIMEOUT_MS, start = { manager.requestLocation() }, stop = { manager.stopUpdatingLocation() })
    private var resolution = 5
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (!awaitingPermission || manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            awaitingPermission = false
            onPermissionResult()
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val fix = didUpdateLocations.lastOrNull() as? CLLocation
            val age = fix?.let { NSDate().timeIntervalSince1970 - it.timestamp.timeIntervalSince1970 }
            val cell = if (fix != null && age != null && age in 0.0..120.0 && fix.horizontalAccuracy >= 0.0 && platformH3Grid().available) {
                fix.coordinate.useContents { platformH3Grid().latLngToCell(latitude, longitude, resolution) }
                    .takeIf { it != 0L }?.let(H3Index::toHex)
            } else {
                null
            }
            finish(cell)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) { finish(null) }
    }

    init { manager.delegate = delegate; manager.desiredAccuracy = kCLLocationAccuracyKilometer }

    fun requestPermission() {
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) { onPermissionResult(); return }
        awaitingPermission = true
        manager.requestWhenInUseAuthorization()
    }

    /** The manager was made on the main thread, so every request and stop runs there. */
    suspend fun cell(resolution: Int): String? = withContext(Dispatchers.Main) {
        this@CellLocation.resolution = resolution
        pending.await()
    }

    private fun finish(cell: String?) = pending.finish(cell)

    fun close() {
        finish(null)
        manager.stopUpdatingLocation()
        awaitingPermission = false
        manager.delegate = null
    }

    private companion object {
        /** Indoors a fix can take long. The card says it failed instead of spinning. */
        const val FIX_TIMEOUT_MS = 15_000L
    }
}
