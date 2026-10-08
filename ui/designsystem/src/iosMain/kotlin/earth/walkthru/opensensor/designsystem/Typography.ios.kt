package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** iOS restarts the app when the user changes the language, so reading it once is enough. */
@Composable
actual fun preferredLanguage(): String = remember {
    NSLocale.preferredLanguages.firstOrNull() as? String ?: ""
}
