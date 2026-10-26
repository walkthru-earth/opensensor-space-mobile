package space.opensensor.designsystem

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Reads Android's system reduce-motion preference. Returns true when "Remove animations" is enabled
 * (`!ValueAnimator.areAnimatorsEnabled()`) or when [Settings.Global.ANIMATOR_DURATION_SCALE] is 0.
 */
fun isSystemReduceMotionEnabled(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return shouldReduceMotion(
        reduceMotion = !ValueAnimator.areAnimatorsEnabled(),
        motionScaleFactor = scale,
    )
}

@Composable
actual fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) { mutableStateOf(isSystemReduceMotionEnabled(context)) }
    DisposableEffect(context) {
        reduceMotion = isSystemReduceMotionEnabled(context)
        val resolver = context.contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = isSystemReduceMotionEnabled(context)
            }
        }
        if (uri != null) {
            runCatching { resolver.registerContentObserver(uri, false, observer) }
        }
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduceMotion
}
