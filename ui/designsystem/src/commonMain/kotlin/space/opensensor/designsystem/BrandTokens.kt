package space.opensensor.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The opensensor.space identity, copied role for role from the website registry.
 *
 * Source: `walkthru-earth.github.io/lib/brand.ts`, palette `sensing` ("Signal blue"), which the
 * website assigns to the `opensensor` project. Role meaning, as the registry documents it.
 * `paper` and `surface` are backgrounds, `main`, `accent` and `deep` are identity colours, `ink`
 * is text on `main` or `accent` whatever the page theme, `text` and `muted` are text on paper or
 * surface, and `border` is for outlines.
 *
 * Change a value here only together with the website registry. `BrandTokensTest` pins every value
 * so an accidental edit fails the build. Air-quality band colours are scientific and live in
 * `Color.kt`, never derived from these.
 */
data class BrandPalette(
    val main: Color,
    val ink: Color,
    val paper: Color,
    val surface: Color,
    val accent: Color,
    val deep: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
)

object BrandTokens {
    /** `brandPalettes.sensing.light` in lib/brand.ts. */
    val Light = BrandPalette(
        main = Color(0xFF71B8F4), // sensing.light.main #71B8F4
        ink = Color(0xFF102F48), // sensing.light.ink #102F48
        paper = Color(0xFFF4FAFF), // sensing.light.paper #F4FAFF
        surface = Color(0xFFDFEEF9), // sensing.light.surface #DFEEF9
        accent = Color(0xFF9ADDE7), // sensing.light.accent #9ADDE7
        deep = Color(0xFF205A80), // sensing.light.deep #205A80
        text = Color(0xFF102F48), // sensing.light.text #102F48
        muted = Color(0xFF44647B), // sensing.light.muted #44647B
        border = Color(0xFFA4C6DE), // sensing.light.border #A4C6DE
    )

    /** `brandPalettes.sensing.dark` in lib/brand.ts. */
    val Dark = BrandPalette(
        main = Color(0xFF71B8F4), // sensing.dark.main #71B8F4
        ink = Color(0xFF102F48), // sensing.dark.ink #102F48
        paper = Color(0xFF131F2A), // sensing.dark.paper #131F2A
        surface = Color(0xFF1D2E3C), // sensing.dark.surface #1D2E3C
        accent = Color(0xFF9ADDE7), // sensing.dark.accent #9ADDE7
        deep = Color(0xFF205A80), // sensing.dark.deep #205A80
        text = Color(0xFFEEF8FF), // sensing.dark.text #EEF8FF
        muted = Color(0xFFA9C4D8), // sensing.dark.muted #A9C4D8
        border = Color(0xFF41617A), // sensing.dark.border #41617A
    )

    /**
     * Error colours are not part of the brand registry. These are the Material 3 baseline error
     * roles, kept apart from both the brand and the air-quality bands.
     */
    val ErrorLight = Color(0xFFB3261E)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFF9DEDC)
    val OnErrorContainerLight = Color(0xFF410E0B)
    val ErrorDark = Color(0xFFF2B8B5)
    val OnErrorDark = Color(0xFF601410)
    val ErrorContainerDark = Color(0xFF8C1D18)
    val OnErrorContainerDark = Color(0xFFF9DEDC)
}
