package earth.walkthru.aqsync.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * `ACCESS_LOCAL_NETWORK` (API 37+, broad permission path in
 * https://developer.android.com/privacy-and-security/local-network-permission).
 *
 * On API 37+ every local-network operation (TCP, UDP, mDNS, [android.net.nsd.NsdManager]) is
 * blocked for apps targeting 37 until this runtime permission is granted. The app asks for it only
 * from a Wi-Fi action on the Device screen ([rememberLocalNetworkAccess]). Bluetooth keeps working
 * without it. Below API 37 the permission does not exist and [isGranted] is true.
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

/** Whether local-network access is held right now, re-checked on resume. Never asks. */
@Composable
fun rememberLanGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LanPermission.isGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = LanPermission.isGranted(context)
        onPauseOrDispose { }
    }
    return granted
}
