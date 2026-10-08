package earth.walkthru.opensensor.discover

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Only ACCESS_COARSE_LOCATION, the one location permission the app manifest declares. Never FINE.
@Composable
internal actual fun rememberApproximateLocationPermission(): ApproximateLocationPermission {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(context, launcher) {
        object : ApproximateLocationPermission {
            override val granted: Boolean get() = context.hasCoarseLocation()

            override fun request(onResult: (Boolean) -> Unit) {
                pending[0] = onResult
                // After a permanent denial Android returns false at once without a prompt.
                launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }
}

private fun Context.hasCoarseLocation(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
