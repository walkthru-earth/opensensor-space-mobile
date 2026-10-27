package space.opensensor.designsystem

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.pm25_corrected
import space.opensensor.designsystem.resources.pm25_correction_info
import space.opensensor.designsystem.resources.pm25_raw
import space.opensensor.family.air.Pm25Basis
import space.opensensor.family.air.WhoGuidelines

/** The short label of a PM2.5 basis, "PM2.5 corrected" or "PM2.5 raw", on every chart and card. */
fun pm25LabelRes(basis: Pm25Basis): StringResource = when (basis) {
    Pm25Basis.CORRECTED -> Res.string.pm25_corrected
    Pm25Basis.RAW -> Res.string.pm25_raw
}

@Composable
fun pm25Label(basis: Pm25Basis): String = stringResource(pm25LabelRes(basis))

/** What corrected and raw mean, one line for the info sheet of a PM2.5 card. */
@Composable
fun pm25CorrectionInfo(): String = stringResource(Res.string.pm25_correction_info)

/** A metric's display label. The PM2.5 views say whether they are raw or corrected. */
@Composable
fun metricLabel(metric: MetricSpec): String = metricLabelRes(metric)?.let { stringResource(it) } ?: metric.label

/**
 * The app label that replaces a metric's registry label, set for the two PM2.5 views and null otherwise.
 * A raw or corrected view of any other quantity keeps its own label.
 */
fun metricLabelRes(metric: MetricSpec): StringResource? =
    metric.basis?.takeIf { metric.property == WhoGuidelines.PM25.propertyId }?.let(::pm25LabelRes)
