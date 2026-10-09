package earth.walkthru.opensensor.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.RuntimeAccess
import earth.walkthru.opensensor.permissions.rememberOpenAppSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
internal actual fun rememberDeviceLocationRequest(): DeviceLocationRequest {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val access = remember { RuntimeAccess(AccessKind.APPROX_LOCATION) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.onPermissionResult() }
    val openSettings = rememberOpenAppSettings()
    access.bind(
        AccessHooks.rationaleBased(
            granted = { context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED },
            rationaleDue = { activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) == true },
            requestPermission = { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            openSettings = openSettings,
        )
    )
    return remember(context, access) {
        DeviceLocationRequest(access) { resolution ->
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
            if (!access.ready || provider == null || !platformH3Grid().available) {
                null
            } else {
                freshCell(manager, provider, context.mainExecutor, resolution)
            }
        }
    }
}

/** Only called after the tap's permission check. Revocation is handled by the caller as a failed fix. */
@SuppressLint("MissingPermission")
private suspend fun freshCell(manager: LocationManager, provider: String, executor: java.util.concurrent.Executor, resolution: Int): String? =
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        val request = LocationRequest.Builder(0).setDurationMillis(20_000).setQuality(LocationRequest.QUALITY_LOW_POWER).build()
        manager.getCurrentLocation(provider, request, cancellation, executor) { fix ->
            if (!continuation.isActive) return@getCurrentLocation
            val age = fix?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
            val cell = if (fix != null && age != null && age in 0..120_000_000_000 && fix.hasAccuracy()) {
                platformH3Grid().latLngToCell(fix.latitude, fix.longitude, resolution).takeIf { it != 0L }?.let(H3Index::toHex)
            } else {
                null
            }
            continuation.resume(cell)
        }
    }
