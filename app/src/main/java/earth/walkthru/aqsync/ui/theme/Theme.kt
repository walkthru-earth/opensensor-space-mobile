package earth.walkthru.aqsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val FallbackDark = darkColorScheme(
    primary = Teal,
    onPrimary = Ink,
    secondary = Mist,
    tertiary = Sand,
    background = Ink,
    surface = Ink,
    surfaceContainer = InkSoft,
    error = Rose,
)

private val FallbackLight = lightColorScheme(
    primary = TealDeep,
    tertiary = Ember,
    error = Rose,
)

/** Rounder, expressive-style corner family. */
private val AqShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Material 3 with the user's wallpaper-derived dynamic colors (always available at minSdk 31) and
 * expressive-leaning typography/shapes. Previews and tests fall back to the ink/teal palette.
 *
 * Note: `MaterialExpressiveTheme`, `MotionScheme` and `expressiveLightColorScheme` are declared
 * `internal` in the Kotlin metadata of material3 1.4.0 (Compose BOM 2026.09.00), so they cannot be
 * called from app code even with the experimental opt-in; switch to them once they are public.
 */
@Composable
fun AqSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val inspection = LocalInspectionMode.current
    val colorScheme = when {
        dynamicColor && !inspection -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AqShapes,
        typography = Typography,
        content = content,
    )
}

/** PM2.5 display band colour (not an AQI; see Color.kt). */
@Composable
fun pm25Band(value: Double?): androidx.compose.ui.graphics.Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value <= 15 -> BandGood
    value <= 35 -> BandModerate
    value <= 55 -> BandElevated
    value <= 150 -> BandHigh
    else -> BandVeryHigh
}
