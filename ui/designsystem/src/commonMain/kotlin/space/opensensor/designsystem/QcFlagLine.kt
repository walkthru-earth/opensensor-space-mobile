package space.opensensor.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.qc_flagged
import space.opensensor.designsystem.resources.qc_flagged_readings
import space.opensensor.designsystem.resources.qc_flatline
import space.opensensor.designsystem.resources.qc_high_humidity
import space.opensensor.designsystem.resources.qc_note
import space.opensensor.designsystem.resources.qc_pm25_above_pm10
import space.opensensor.designsystem.resources.qc_spike
import space.opensensor.family.air.QcFlags
import space.opensensor.family.air.QcRules

/** One line per quality check that [flags] failed, in bit order (#91). */
fun qcReasonRes(flags: Int): List<StringResource> = listOfNotNull(
    Res.string.qc_pm25_above_pm10.takeIf { flags and QcFlags.PM25_ABOVE_PM10 != 0 },
    Res.string.qc_flatline.takeIf { flags and QcFlags.FLATLINE != 0 },
    Res.string.qc_spike.takeIf { flags and QcFlags.SPIKE != 0 },
    Res.string.qc_high_humidity.takeIf { flags and QcFlags.HIGH_HUMIDITY != 0 },
)

/** The format argument of a [qcReasonRes] line, the [QcRules] constant it names, or null when it takes none. */
fun qcReasonArg(res: StringResource): Int? = when (res) {
    Res.string.qc_flatline -> QcRules.FLATLINE_ROWS
    Res.string.qc_high_humidity -> QcRules.HIGH_HUMIDITY_PERCENT.toInt()
    else -> null
}

/** The reasons of [flags] in words, with the rule constants filled in. */
@Composable
fun qcReasons(flags: Int): List<String> = qcReasonRes(flags).map { res ->
    val arg = qcReasonArg(res)
    if (arg == null) stringResource(res) else stringResource(res, arg)
}

/**
 * Detailed and Developer only. One short line that readings are suspect, "Flagged as suspect", or
 * "12 readings flagged" when [rows] is given, with an info icon that lists each check of [flags]
 * (#91). Values stay as measured, which the sheet says last.
 */
@Composable
fun QcFlagLine(flags: Int, modifier: Modifier = Modifier, rows: Long? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val title = stringResource(Res.string.qc_flagged)
    val line = if (rows == null) title else pluralStringResource(Res.plurals.qc_flagged_readings, rows.toQuantity(), rows)
    val body = qcReasons(flags) + stringResource(Res.string.qc_note)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BodyText(line, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f, fill = false))
        InfoButton(InfoContent(title, body), tint = color)
    }
}

private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
