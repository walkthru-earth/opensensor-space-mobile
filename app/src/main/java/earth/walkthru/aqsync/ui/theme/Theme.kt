package earth.walkthru.aqsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.opensensor.designsystem.BrandDarkScheme
import earth.walkthru.opensensor.designsystem.BrandLightScheme
import earth.walkthru.opensensor.designsystem.BrandShapes
import earth.walkthru.opensensor.designsystem.rememberBrandTypography

/**
 * opensensor.space theme. The brand palette is the default on every phone: the app's identity is
 * the same as the website's, so wallpaper-derived dynamic colour is opt-in through [dynamicColor]
 * and nothing in the app enables it today. Air-quality bands never follow the theme. Fonts and the
 * type scale come from `:ui:designsystem` ([rememberBrandTypography]), shared with iOS.
 *
 * Note: `MaterialExpressiveTheme`, `MotionScheme` and `expressiveLightColorScheme` are declared
 * `internal` in the Kotlin metadata of material3 1.4.0 (Compose BOM 2026.09.00), so they cannot be
 * called from app code even with the experimental opt-in. Switch to them once they are public.
 */
@Composable
fun AqSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val inspection = LocalInspectionMode.current
    val colorScheme = when {
        dynamicColor && !inspection -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDarkScheme
        else -> BrandLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = BrandShapes,
        typography = rememberBrandTypography(),
        content = content,
    )
}

/**
 * PM2.5 WHO step colour (not an AQI, see Color.kt). Delegates to `AirMood.ofOrNull` so the edges
 * and their boundary rule (inclusive upper edge) exist in exactly one place, `:core:airquality`.
 */
@Composable
fun pm25Band(value: Double?): Color =
    value?.let { AirMood.ofOrNull(it)?.color() } ?: MaterialTheme.colorScheme.onSurfaceVariant
