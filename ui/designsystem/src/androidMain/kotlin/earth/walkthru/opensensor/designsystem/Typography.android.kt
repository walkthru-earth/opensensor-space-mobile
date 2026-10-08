package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
actual fun preferredLanguage(): String {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) "" else locales[0].language
}
