package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

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
 * The one content card radius, 32 dp like the website's `.brand-panel` (2rem). Every content card
 * on both platforms uses it ([BrandCard]). It is also the theme's `shapes.extraLarge`.
 */
val CardShape: RoundedCornerShape = RoundedCornerShape(32.dp)

/**
 * Large rounded corners like the website's cards (`.brand-panel` is 2rem, `.brand-icon` 1.15rem).
 * Buttons, chips and badges use the `FloatingControl` card curves, never a full pill or circle.
 */
val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = CardShape,
)

/**
 * The opensensor.space theme for shared Compose code: brand colours, shapes and the bundled brand
 * type scale ([rememberBrandTypography], Cairo for Arabic-script locales). The Android app and the
 * iOS framework both use this theme, so neither platform falls back to the Material baseline purple
 * or the system font. Wallpaper-derived dynamic colour is never used, the app's identity is the
 * website's. Air-quality step colours never follow the theme.
 */
@Composable
fun OpenSensorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    typography: Typography = rememberBrandTypography(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) BrandDarkScheme else BrandLightScheme,
        shapes = BrandShapes,
        typography = typography,
        content = content,
    )
}
