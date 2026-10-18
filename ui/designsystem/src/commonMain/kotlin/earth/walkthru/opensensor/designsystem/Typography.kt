package earth.walkthru.opensensor.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.walkthru.opensensor.designsystem.resources.Res
import earth.walkthru.opensensor.designsystem.resources.cairo
import earth.walkthru.opensensor.designsystem.resources.quicksand
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/** Tabular figures where a font has them, so live numbers do not jitter as digits change. */
const val TABULAR = "tnum"

/**
 * Shared line and scale limits so system font scaling up to 200 percent wraps instead of clipping
 * headings, labels, and display numerals on 320 dp phones.
 */
object TypographyLimits {
    val EyebrowMaxLines: Int = 2
    val StatLabelMaxLines: Int = 3
    val StatValueMaxLines: Int = 2
    val TabLabelMaxLines: Int = 2
    val TickingMaxLines: Int = 2

    /**
     * Maximum effective scale multiplier for display numerals (`displayLarge` 84 sp, `displayMedium`
     * 48 sp, `displaySmall` 32 sp). Body, label, title, and headline styles scale all the way to 200
     * percent (`fontScale = 2.0f`), while display numerals cap at 1.35x in converted `dp` so 3-digit
     * readings fit on 320 dp screens without horizontal clipping and never shrink below their 1.0x
     * `dp` size under Android 14 non-linear font scaling.
     */
    val MaxDisplayFontScale: Float = 1.35f
}

/**
 * Clamps a display numeral size of [baseSp] using [density]'s actual `sp` to `dp` converter
 * (including Android 14 and later non-linear font scaling) so the rendered `dp` size never drops
 * below [baseSp] dp when `fontScale >= 1f` and never exceeds
 * [TypographyLimits.MaxDisplayFontScale] times [baseSp] dp.
 */
fun clampedDisplaySizeSp(baseSp: Float, density: Density): TextUnit = with(density) {
    val minDp = baseSp.dp
    val maxDp = (baseSp * TypographyLimits.MaxDisplayFontScale).dp
    val targetDp = if (fontScale <= 1f) {
        baseSp.sp.toDp().coerceAtMost(minDp)
    } else {
        baseSp.sp.toDp().coerceIn(minDp, maxDp)
    }
    targetDp.toSp()
}

/** Quicksand's variable `wght` axis (300 to 700). */
internal val QuicksandWeights = listOf(300, 400, 500, 600, 700)

/** The Cairo weights the type scale uses (its `wght` axis runs 200 to 1000). */
internal val CairoWeights = listOf(400, 500, 600, 700, 800)

/**
 * One bundled variable TTF per face, pinned to each weight through the `wght` axis. Compose
 * resources pass [FontVariation.Settings] to Android's `Typeface` builder and to Skia's
 * `Typeface.makeClone` on iOS, and the font cache key includes the weight and the settings, so
 * every weight is its own instance on both platforms.
 */
@Composable
private fun variableFamily(resource: FontResource, weights: List<Int>): FontFamily = FontFamily(
    weights.map { weight ->
        Font(
            resource,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/**
 * Quicksand, the website's heading and body face (`app/globals.css`, `--font-quicksand`), bundled
 * as the OFL variable TTF from google/fonts `ofl/quicksand`. The app works offline, so nothing is
 * downloaded at runtime. The license text ships in `composeResources/files/licenses/`.
 */
@Composable
fun quicksandFontFamily(): FontFamily = variableFamily(Res.font.quicksand, QuicksandWeights)

/**
 * Cairo, the website's Arabic face (`--font-cairo`), the OFL variable TTF `Cairo[slnt,wght].ttf`
 * version 3.130 from google/fonts `ofl/cairo` (byte for byte the website's `app/fonts/` copy). Used
 * for the whole UI when the language is written in Arabic script or the layout runs right to left.
 *
 * It also sets the big numbers. Quicksand has proportional digits and no `tnum` feature, so a
 * 10-second reading would shift sideways as it changes. Cairo's Latin digits all share one advance
 * width, which keeps live values still without a system font that breaks the brand look.
 */
@Composable
fun cairoFontFamily(): FontFamily = variableFamily(Res.font.cairo, CairoWeights)

/** Cairo's tall Arabic ascent and descent would pad big numbers, so trim the line box to the digits. */
private val TrimmedNumbers = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private val ArabicScriptLanguages = setOf("ar", "fa", "ur", "ps", "ckb", "sd", "ug")

/**
 * Languages written in Arabic script get Cairo for all text. Accepts a bare ISO 639 code or a
 * BCP 47 tag such as `ar-EG` or `ckb_IQ`.
 */
fun usesArabicScript(language: String): Boolean =
    language.substringBefore('-').substringBefore('_').lowercase() in ArabicScriptLanguages

/**
 * Cairo sets the text when the language is written in Arabic script or the layout runs right to
 * left. Latin left-to-right languages keep Quicksand.
 */
fun usesCairoText(language: String, direction: LayoutDirection): Boolean =
    usesArabicScript(language) || direction == LayoutDirection.Rtl

/**
 * The app's language, read where Compose resources read it (`Locale.current`), so the font always
 * matches the strings on screen. Each platform first moves the language its own resources resolved
 * to the front (Android `AppLanguage`, iOS `AppLanguage` in `AppDelegate.swift`), so a phone set to
 * French then Arabic gets Arabic strings, Cairo and a right-to-left layout together.
 */
@Composable
fun preferredLanguage(): String = Locale.current.language

/**
 * The type scale. Headings are bold and rounded like the website (`.brand-page h1..h4` are weight
 * 700 with slightly negative tracking). Body text stays at Material sizes for a phone. Display
 * styles carry numbers and use [numbers], clamping their rendered `dp` size through [density] so
 * they never drop below their 1.0x `dp` size and never exceed
 * [TypographyLimits.MaxDisplayFontScale] times that size.
 */
fun brandTypography(text: FontFamily, numbers: FontFamily, density: Density = Density(1f, 1f)): Typography {
    val base = Typography()
    fun TextStyle.brand(weight: FontWeight) = copy(fontFamily = text, fontWeight = weight)
    fun number(weight: FontWeight, size: Int, lineHeight: Int, tracking: Double): TextStyle {
        val clampedFontSize = clampedDisplaySizeSp(size.toFloat(), density)
        val scaleRatio = clampedFontSize.value / size.toFloat()
        return TextStyle(
            fontFamily = numbers,
            fontWeight = weight,
            fontSize = clampedFontSize,
            lineHeight = (lineHeight * scaleRatio).sp,
            letterSpacing = (tracking * scaleRatio).sp,
            fontFeatureSettings = TABULAR,
            lineHeightStyle = TrimmedNumbers,
        )
    }
    return Typography(
        displayLarge = number(FontWeight.ExtraBold, 84, 88, -2.0),
        displayMedium = number(FontWeight.Bold, 48, 54, -1.0),
        displaySmall = number(FontWeight.SemiBold, 32, 38, -0.5),
        headlineLarge = base.headlineLarge.brand(FontWeight.Bold).copy(letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.brand(FontWeight.Bold).copy(letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.brand(FontWeight.Bold),
        titleLarge = base.titleLarge.brand(FontWeight.Bold),
        titleMedium = base.titleMedium.brand(FontWeight.Bold),
        titleSmall = base.titleSmall.brand(FontWeight.Bold),
        bodyLarge = base.bodyLarge.brand(FontWeight.Medium),
        bodyMedium = base.bodyMedium.brand(FontWeight.Medium),
        bodySmall = base.bodySmall.brand(FontWeight.Medium),
        labelLarge = base.labelLarge.brand(FontWeight.Bold),
        labelMedium = base.labelMedium.brand(FontWeight.Bold),
        labelSmall = base.labelSmall.brand(FontWeight.Bold).copy(letterSpacing = 0.8.sp),
    )
}

/**
 * The brand type scale for the current locale and [density]. Quicksand text, or Cairo for
 * Arabic-script languages and right-to-left layouts, with Cairo display numbers either way.
 */
@Composable
fun rememberBrandTypography(
    arabicScript: Boolean = usesCairoText(preferredLanguage(), LocalLayoutDirection.current),
    density: Density = LocalDensity.current,
): Typography {
    val quicksand = quicksandFontFamily()
    val cairo = cairoFontFamily()
    return remember(arabicScript, quicksand, cairo, density) {
        brandTypography(text = if (arabicScript) cairo else quicksand, numbers = cairo, density = density)
    }
}
