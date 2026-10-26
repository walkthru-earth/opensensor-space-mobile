package space.opensensor.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import space.opensensor.airquality.WhoStep
import space.opensensor.data.history.AirMood
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.moodWord
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.who_step_above_it1
import space.opensensor.history.resources.who_step_guideline
import space.opensensor.history.resources.who_step_it1
import space.opensensor.history.resources.who_step_it2
import space.opensensor.history.resources.who_step_it3
import space.opensensor.history.resources.who_step_it4
import space.opensensor.designsystem.color as designSystemColor
import space.opensensor.designsystem.contrastRatio as designSystemContrastRatio
import space.opensensor.designsystem.onBand as designSystemOnBand
import space.opensensor.designsystem.relativeLuminance as designSystemRelativeLuminance
import space.opensensor.designsystem.textColorOnSurface as designSystemTextColorOnSurface

// History's WHO colour helpers delegate to :ui:designsystem (ColorContrast.kt). The ARGB values live
// only in :core:airquality (WhoStep).

internal fun WhoStep.color(): Color = designSystemColor()

internal fun AirMood.color(): Color = step.color()

/** The step colour of [value] on this metric's scale, or [neutral] for a metric without one. */
internal fun MetricSpec.valueColor(value: Double, neutral: Color): Color = stepOf(value)?.color() ?: neutral

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

@Composable
internal fun WhoStep.labelText(): String = stringResource(
    when (this) {
        WhoStep.GUIDELINE -> Res.string.who_step_guideline
        WhoStep.IT4 -> Res.string.who_step_it4
        WhoStep.IT3 -> Res.string.who_step_it3
        WhoStep.IT2 -> Res.string.who_step_it2
        WhoStep.IT1 -> Res.string.who_step_it1
        WhoStep.ABOVE_IT1 -> Res.string.who_step_above_it1
    },
)
