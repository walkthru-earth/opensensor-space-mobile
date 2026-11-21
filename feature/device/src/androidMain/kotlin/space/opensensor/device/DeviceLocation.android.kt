package space.opensensor.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.rememberOpenAppSettings
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.platformH3Grid
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
            val coarseProviders = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                .filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
            val providers = coarseProviders.ifEmpty {
                listOf(LocationManager.GPS_PROVIDER).filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
            }
            if (!access.ready || providers.isEmpty() || !platformH3Grid().available) {
                null
            } else {
                raceFreshCell(manager, providers, context.mainExecutor, resolution)
                    ?: lastKnownCell(manager, providers, resolution)
            }
        }
    }
}

/** Races enabled coarse providers so whichever delivers a fresh cell first wins and cancels the rest. */
private suspend fun raceFreshCell(
    manager: LocationManager,
    providers: List<String>,
    executor: java.util.concurrent.Executor,
    resolution: Int,
): String? = coroutineScope {
    val winner = CompletableDeferred<String?>()
    val jobs = providers.map { provider ->
        launch {
            val cell = freshCell(manager, provider, executor, resolution, FIX_TIMEOUT_MS)
            if (cell != null) winner.complete(cell)
        }
    }
    val finish = launch {
        jobs.forEach { it.join() }
        winner.complete(null)
    }
    try {
        winner.await()
    } finally {
        finish.cancel()
        jobs.forEach { it.cancel() }
    }
}

/**
 * Only called after the tap's permission check. Uses balanced power so GMS actively resolves a
 * Wi-Fi/cell fix indoors (Android still coarsens the result under ACCESS_COARSE_LOCATION).
 * Revocation is handled by the caller as a failed fix.
 */
@SuppressLint("MissingPermission")
private suspend fun freshCell(
    manager: LocationManager,
    provider: String,
    executor: java.util.concurrent.Executor,
    resolution: Int,
    durationMs: Long,
): String? = suspendCancellableCoroutine { continuation ->
    val cancellation = CancellationSignal()
    continuation.invokeOnCancellation { cancellation.cancel() }
    val request = LocationRequest.Builder(0)
        .setDurationMillis(durationMs)
        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
        .build()
    try {
        manager.getCurrentLocation(provider, request, cancellation, executor) { fix ->
            if (!continuation.isActive) return@getCurrentLocation
            continuation.resume(fix?.toCell(FRESH_MAX_AGE_NS, resolution))
        }
    } catch (_: SecurityException) {
        if (continuation.isActive) continuation.resume(null)
    }
}

/** Fallback when active fixes time out. Only pans the map camera; never drafts or saves a cell. */
@SuppressLint("MissingPermission")
private fun lastKnownCell(manager: LocationManager, providers: List<String>, resolution: Int): String? =
    providers.asSequence()
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }
        ?.toCell(LAST_KNOWN_MAX_AGE_NS, resolution)

private fun Location.toCell(maxAgeNs: Long, resolution: Int): String? {
    val age = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
    if (age !in 0..maxAgeNs || !hasAccuracy()) return null
    return platformH3Grid().latLngToCell(latitude, longitude, resolution).takeIf { it != 0L }?.let(H3Index::toHex)
}

private const val FIX_TIMEOUT_MS = 15_000L
private const val FRESH_MAX_AGE_NS = 120_000_000_000L
private const val LAST_KNOWN_MAX_AGE_NS = 900_000_000_000L
