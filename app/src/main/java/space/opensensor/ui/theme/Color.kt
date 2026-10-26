package space.opensensor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import space.opensensor.airquality.WhoStep
import space.opensensor.data.history.AirMood
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.color as designSystemColor
import space.opensensor.designsystem.contrastRatio as designSystemContrastRatio
import space.opensensor.designsystem.onBand as designSystemOnBand
import space.opensensor.designsystem.onColor as designSystemOnColor
import space.opensensor.designsystem.relativeLuminance as designSystemRelativeLuminance
import space.opensensor.designsystem.textColorOnSurface as designSystemTextColorOnSurface

/**
 * The one mapping from a WHO step to its colour, delegating to `:ui:designsystem`
 * (`ColorContrast.kt`). Every air-quality colour in the app (bars, the calendar, the day ring, the
 * mood face and pill) goes through here, so a value can never be named one step and painted
 * another. The ARGB values and their colour-blind checks live in `:core:airquality` (`WhoStep`).
 */
fun WhoStep.color(): Color = designSystemColor()

/** A mood paints its WHO step. */
fun AirMood.color(): Color = step.color()

/** The step colour of [value] on [metric]'s scale, or [neutral] for a metric without one. */
fun MetricSpec.valueColor(value: Double, neutral: Color): Color = stepOf(value)?.color() ?: neutral

/** Dark ink used for text and face features drawn on a light band. */
val BandInkDark = space.opensensor.designsystem.BandInkDark

/** Light ink used on the darkest band. */
val BandInkLight = space.opensensor.designsystem.BandInkLight

/**
 * Text or face features drawn **on** a band colour: whichever of dark or light ink contrasts more.
 * The band itself never changes; only the ink on top of it adapts (see `BrandTokensTest`).
 */
fun AirMood.onColor(): Color = step.onColor()

fun WhoStep.onColor(): Color = designSystemOnColor()

fun onBand(band: Color): Color = designSystemOnBand(band)

/**
 * A band colour used **as text** on the current surface. Large text needs 3:1 (WCAG 1.4.3); a band
 * that cannot reach it on this theme's surface (yellow on light paper, maroon on dark paper)
 * falls back to the normal text colour, and the screen shows the band as a swatch instead.
 */
@Composable
fun AirMood.textColorOnSurface(): Color = designSystemTextColorOnSurface(LARGE_TEXT_CONTRAST)

const val LARGE_TEXT_CONTRAST = space.opensensor.designsystem.LARGE_TEXT_CONTRAST
const val BODY_TEXT_CONTRAST = space.opensensor.designsystem.BODY_TEXT_CONTRAST

/** WCAG 2 relative luminance of an opaque sRGB colour. */
fun relativeLuminance(c: Color): Double = designSystemRelativeLuminance(c)

/** WCAG 2 contrast ratio, 1 to 21. */
fun contrastRatio(a: Color, b: Color): Double = designSystemContrastRatio(a, b)
