package earth.walkthru.aqsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.AirMood

/**
 * Brand palette to Material 3 roles. Light follows the website's light page: warm `paper`
 * background, `surface` cards, and the primary action as a dark `ink` pill. Dark follows the
 * website's dark page, where the pill turns `main` blue with `ink` text (ink is text on main in
 * both themes). Container steps between paper and surface are interpolated, not new colours.
 */
fun brandColorScheme(p: BrandPalette, dark: Boolean): ColorScheme {
    fun step(t: Float) = lerp(p.paper, p.surface, t)
    return if (!dark) {
        lightColorScheme(
            primary = p.ink,
            onPrimary = p.paper,
            primaryContainer = p.main,
            onPrimaryContainer = p.ink,
            inversePrimary = p.main,
            secondary = p.deep,
            onSecondary = Color.White,
            secondaryContainer = p.surface,
            onSecondaryContainer = p.text,
            tertiary = p.deep,
            onTertiary = Color.White,
            tertiaryContainer = p.accent,
            onTertiaryContainer = p.ink,
            background = p.paper,
            onBackground = p.text,
            surface = p.paper,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.muted,
            surfaceTint = p.deep,
            surfaceBright = p.paper,
            surfaceDim = p.surface,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = step(0.35f),
            surfaceContainer = step(0.65f),
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = lerp(p.surface, p.border, 0.25f),
            outline = p.muted,
            outlineVariant = p.border,
            inverseSurface = p.ink,
            inverseOnSurface = p.paper,
            error = BrandTokens.ErrorLight,
            onError = BrandTokens.OnErrorLight,
            errorContainer = BrandTokens.ErrorContainerLight,
            onErrorContainer = BrandTokens.OnErrorContainerLight,
            scrim = Color.Black,
        )
    } else {
        darkColorScheme(
            primary = p.main,
            onPrimary = p.ink,
            primaryContainer = p.deep,
            onPrimaryContainer = p.text,
            inversePrimary = p.deep,
            secondary = p.accent,
            onSecondary = p.ink,
            secondaryContainer = lerp(p.surface, p.deep, 0.45f),
            onSecondaryContainer = p.text,
            tertiary = p.accent,
            onTertiary = p.ink,
            tertiaryContainer = p.deep,
            onTertiaryContainer = p.text,
            background = p.paper,
            onBackground = p.text,
            surface = p.paper,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.muted,
            surfaceTint = p.main,
            surfaceBright = lerp(p.surface, p.border, 0.3f),
            surfaceDim = p.paper,
            surfaceContainerLowest = lerp(p.paper, Color.Black, 0.3f),
            surfaceContainerLow = step(0.45f),
            surfaceContainer = p.surface,
            surfaceContainerHigh = lerp(p.surface, p.border, 0.2f),
            surfaceContainerHighest = lerp(p.surface, p.border, 0.35f),
            outline = p.muted,
            outlineVariant = p.border,
            inverseSurface = p.text,
            inverseOnSurface = p.paper,
            error = BrandTokens.ErrorDark,
            onError = BrandTokens.OnErrorDark,
            errorContainer = BrandTokens.ErrorContainerDark,
            onErrorContainer = BrandTokens.OnErrorContainerDark,
            scrim = Color.Black,
        )
    }
}

val BrandLightScheme = brandColorScheme(BrandTokens.Light, dark = false)
val BrandDarkScheme = brandColorScheme(BrandTokens.Dark, dark = true)

/**
 * Large rounded corners like the website's cards (`.brand-panel` is 2rem, `.brand-icon` 1.15rem).
 * Pills use `CircleShape`.
 */
val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * opensensor.space theme. The brand palette is the default on every phone: the app's identity is
 * the same as the website's, so wallpaper-derived dynamic colour is opt-in through [dynamicColor]
 * and nothing in the app enables it today. Air-quality bands never follow the theme.
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
    val locales = LocalConfiguration.current.locales
    val arabic = !locales.isEmpty && usesArabicScript(locales[0].language)
    val typography = remember(arabic) { if (arabic) brandTypography(text = Cairo) else Typography }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = BrandShapes,
        typography = typography,
        content = content,
    )
}

/**
 * PM2.5 display band colour (not an AQI, see Color.kt). Delegates to `AirMood.of` so the edges and
 * their boundary rule (exclusive upper bound) exist in exactly one place.
 */
@Composable
fun pm25Band(value: Double?): Color =
    value?.let { AirMood.of(it).color() } ?: MaterialTheme.colorScheme.onSurfaceVariant
