package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable
import earth.walkthru.aqsync.airquality.Pm25Basis
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.opensensor.designsystem.resources.Res
import earth.walkthru.opensensor.designsystem.resources.pm25_corrected
import earth.walkthru.opensensor.designsystem.resources.pm25_correction_info
import earth.walkthru.opensensor.designsystem.resources.pm25_raw
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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

/** The app label that replaces a metric's registry label, set for the two PM2.5 views and null otherwise. */
fun metricLabelRes(metric: MetricSpec): StringResource? = metric.basis?.let(::pm25LabelRes)
