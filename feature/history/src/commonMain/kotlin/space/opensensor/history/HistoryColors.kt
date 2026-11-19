package space.opensensor.history

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.moodWord
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import space.opensensor.family.air.stepOf
import space.opensensor.designsystem.color as designSystemColor
import space.opensensor.designsystem.contrastRatio as designSystemContrastRatio
import space.opensensor.designsystem.onBand as designSystemOnBand
import space.opensensor.designsystem.relativeLuminance as designSystemRelativeLuminance
import space.opensensor.designsystem.textColorOnSurface as designSystemTextColorOnSurface

// History's WHO colour helpers delegate to :ui:designsystem (ColorContrast.kt). The ARGB values live
// only in :family:air (WhoStep).

internal fun WhoStep.color(): Color = designSystemColor()

internal fun AirMood.color(): Color = step.color()

/** The step colour of [value] on this metric's scale, or [neutral] for a metric without one. */
internal fun MetricSpec.valueColor(value: Double, neutral: Color): Color = stepOf(value)?.color() ?: neutral

/** The colours under a WHO mark. [track] sits under the see-through step colour, [border] outlines it with 3 to 1 on the card. */
internal data class MarkColors(val border: Color, val track: Color)

internal fun markColors(scheme: ColorScheme): MarkColors = MarkColors(border = scheme.outline, track = scheme.surfaceContainerHighest)

/**
 * How much of a mark a WHO step fills, the cue that does not rely on colour. The cleanest step fills
 * a little over a third, each step after it more, the last step all of it. A metric without a WHO
 * scale (null) fills the whole mark.
 */
internal fun whoStepShare(step: WhoStep?): Float =
    if (step == null) 1f else MIN_STEP_SHARE + (1f - MIN_STEP_SHARE) * step.ordinal / (WhoStep.entries.size - 1)

private const val MIN_STEP_SHARE = 0.35f

/** Ink drawn on a band colour, whichever of dark or light contrasts more. */
internal fun onBand(band: Color): Color = designSystemOnBand(band)

/** A band colour used as large text on the surface, or the normal text colour when it cannot reach 3:1. */
@Composable
internal fun AirMood.textColorOnSurface(): Color = designSystemTextColorOnSurface(LARGE_TEXT_CONTRAST)

internal const val LARGE_TEXT_CONTRAST = space.opensensor.designsystem.LARGE_TEXT_CONTRAST

/** WCAG 2 relative luminance of an opaque sRGB colour. */
internal fun relativeLuminance(c: Color): Double = designSystemRelativeLuminance(c)

/** WCAG 2 contrast ratio, 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Double = designSystemContrastRatio(a, b)

@Composable
internal fun AirMood.titleText(): String = moodWord(step)

/**
 * The name this metric's scale gives the step [value] falls in, in [language], or null for a metric
 * without a scale or a non-finite value. The words are the scale's own (the stac-opensensor registry's
 * for a family scale, the Collection's for a feed), so the app keeps no copy of them.
 */
internal fun MetricSpec.stepName(value: Double, language: String): String? = healthScale?.stepName(value, language)
