package space.opensensor.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** One count per activity window, so two credential fields share one `FLAG_SECURE`. */
private val secureWindows = WeakHashMap<Window, CaptureGuardCount>()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal actual object PlatformCredentialCaptureGuard : CredentialCaptureGuard {
    @Composable
    override fun guard(onScreenshot: () -> Unit): Boolean {
        val window = LocalContext.current.findActivity()?.window
        DisposableEffect(window) {
            if (window != null && secureWindows.getOrPut(window) { CaptureGuardCount() }.acquire()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (window != null && secureWindows[window]?.release() == true) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureWindows.remove(window)
                }
            }
        }
        // FLAG_SECURE already blanks every capture, so the field never needs to force its mask, and a
        // screenshot holds no secret to hide again.
        return false
    }
}
