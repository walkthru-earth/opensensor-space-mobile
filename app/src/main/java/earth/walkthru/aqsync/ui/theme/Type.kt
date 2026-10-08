package earth.walkthru.aqsync.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import earth.walkthru.aqsync.R

/** Tabular figures where a font has them, so live numbers do not jitter as digits change. */
const val TABULAR = "tnum"

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * Quicksand, the website's heading and body face (`app/globals.css`, `--font-quicksand`), bundled
 * as the OFL variable TTF from google/fonts `ofl/quicksand` (weights 300 to 700). The app works
 * offline, so nothing is downloaded at runtime. License text ships in `assets/licenses/`.
 */
val Quicksand = FontFamily(
    variable(R.font.quicksand, 300),
    variable(R.font.quicksand, 400),
    variable(R.font.quicksand, 500),
    variable(R.font.quicksand, 600),
    variable(R.font.quicksand, 700),
)

/**
 * Cairo, the website's Arabic face (`--font-cairo`), the variable TTF copied from the website's
 * `app/fonts/`. Used for the whole UI when the phone's language is written in Arabic script.
 *
 * It also sets the big numbers. Quicksand has proportional digits and no `tnum` feature, so a
 * 10-second reading would shift sideways as it changes. Cairo's Latin digits all share one advance
 * width, which keeps live values still without a system font that breaks the brand look.
 */
val Cairo = FontFamily(
    variable(R.font.cairo, 400),
    variable(R.font.cairo, 500),
    variable(R.font.cairo, 600),
    variable(R.font.cairo, 700),
    variable(R.font.cairo, 800),
)

/** The face for live and summary numbers (fixed-width digits). */
val NumberFamily: FontFamily = Cairo

/** Languages written in Arabic script get Cairo for all text. */
fun usesArabicScript(language: String): Boolean = language in setOf("ar", "fa", "ur", "ps", "ckb", "sd", "ug")

/**
 * The type scale. Headings are bold and rounded like the website (`.brand-page h1..h4` are weight
 * 700 with slightly negative tracking); body text stays at Material sizes for a phone. Display
 * styles carry numbers and use [NumberFamily].
 */
fun brandTypography(text: FontFamily = Quicksand, numbers: FontFamily = NumberFamily): Typography {
    val base = Typography()
    fun TextStyle.brand(weight: FontWeight) = copy(fontFamily = text, fontWeight = weight)
    return Typography(
        displayLarge = TextStyle(
            fontFamily = numbers,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 84.sp,
            lineHeight = 88.sp,
            letterSpacing = (-2).sp,
            fontFeatureSettings = TABULAR,
        ),
        displayMedium = TextStyle(
            fontFamily = numbers,
            fontWeight = FontWeight.Bold,
            fontSize = 48.sp,
            lineHeight = 54.sp,
            letterSpacing = (-1).sp,
            fontFeatureSettings = TABULAR,
        ),
        displaySmall = TextStyle(
            fontFamily = numbers,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.5).sp,
            fontFeatureSettings = TABULAR,
        ),
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

val Typography: Typography = brandTypography()
