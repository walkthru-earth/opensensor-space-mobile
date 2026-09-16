package earth.walkthru.aqsync.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * `ACCESS_LOCAL_NETWORK` handling (Path B — broad permission — in
 * https://developer.android.com/privacy-and-security/local-network-permission).
 *
 * On API 37+ every local-network operation (TCP, UDP, mDNS, [android.net.nsd.NsdManager]) is
 * blocked for apps targeting 37 until this runtime permission is granted. It sits in the
 * NEARBY_DEVICES group together with `BLUETOOTH_SCAN`/`CONNECT`, so once Bluetooth was allowed it is
 * usually granted silently — but we still check and request before any LAN action, and everything
 * LAN-related is hidden or disabled with an explanation when it is denied. Bluetooth keeps working.
 *
 * Below API 37 the permission does not exist: `INTERNET` (install-time) is all a socket needs, so
 * [isGranted] is simply true and nothing is requested.
 */
object LanPermission {
    /** The API level that introduced the permission. */
    const val MIN_API = 37

    val name: String? = if (Build.VERSION.SDK_INT >= MIN_API) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    fun isGranted(context: Context): Boolean {
        val perm = name ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

/** Observable LAN permission state plus a `request()` that is a no-op where the permission does not exist. */
class LanPermissionState internal constructor(
    val granted: Boolean,
    /** True when the user has refused at least once in this composition. */
    val deniedOnce: Boolean,
    /** False below API 37: there is nothing to ask for. */
    val requestable: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberLanPermission(): LanPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LanPermission.isGranted(context)) }
    var deniedOnce by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        granted = LanPermission.isGranted(context)
        onPauseOrDispose { }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) deniedOnce = true
    }
    val perm = LanPermission.name
    return LanPermissionState(
        granted = granted,
        deniedOnce = deniedOnce,
        requestable = perm != null,
        request = { if (perm != null) launcher.launch(perm) },
    )
}
