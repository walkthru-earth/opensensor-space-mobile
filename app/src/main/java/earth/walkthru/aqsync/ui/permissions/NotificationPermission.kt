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

/**
 * `POST_NOTIFICATIONS` (API 33+). The sync's dataSync foreground service posts an ongoing progress
 * notification; without the permission Android runs the service anyway and simply hides it. So the
 * permission is asked for once, right before the first user-started sync, and the sync proceeds
 * whatever the answer. Below API 33 there is nothing to ask.
 */
object NotificationPermission {
    val name: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    fun isGranted(context: Context): Boolean {
        val perm = name ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

class NotificationPermissionState internal constructor(
    val granted: Boolean,
    /** Run [action] now if granted or not requestable, else ask once and run it after the answer. */
    val ensureThen: (action: () -> Unit) -> Unit,
)

@Composable
fun rememberNotificationPermission(): NotificationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    var asked by remember { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        pending[0]?.invoke()
        pending[0] = null
    }
    val perm = NotificationPermission.name
    return NotificationPermissionState(
        granted = granted,
        ensureThen = { action ->
            if (granted || perm == null || asked) {
                action()
            } else {
                asked = true
                pending[0] = action
                launcher.launch(perm)
            }
        },
    )
}
