package space.opensensor.discover

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.rememberOpenAppSettings

// Only ACCESS_COARSE_LOCATION, the one location permission the app manifest declares. Never FINE.
// After a permanent denial Android answers at once without a prompt and no rationale is due, so the
// shared flow offers the app's settings page.
@Composable
internal actual fun rememberApproximateLocationAccess(): RuntimeAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val access = remember { RuntimeAccess(AccessKind.APPROX_LOCATION) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.onPermissionResult() }
    val openSettings = rememberOpenAppSettings()
    access.bind(
        AccessHooks.rationaleBased(
            granted = { context.hasCoarseLocation() },
            rationaleDue = { activity?.shouldShowRequestPermissionRationale(COARSE) == true },
            requestPermission = { launcher.launch(COARSE) },
            openSettings = openSettings,
        ),
    )
    return access
}

private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

private fun Context.hasCoarseLocation(): Boolean = checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED
