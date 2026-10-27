package space.opensensor.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.LiveReading
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.CardTone
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.StatusDot
import space.opensensor.model.AppProfile
import space.opensensor.model.ThingDatastream
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.env_unsupported
import space.opensensor.today.resources.metrics_title
import space.opensensor.today.resources.now_last_reading
import space.opensensor.today.resources.now_no_main_reading
import space.opensensor.today.resources.now_right_now
import space.opensensor.today.resources.now_waiting
import space.opensensor.units.UnitFormatter

/**
 * [value] in [column]'s display units, rounded to the profile's display step. A unit the reader's
 * system converts keeps its own decimals.
 */
internal fun formatReading(value: Double?, column: ThingDatastream, units: UnitFormatter): String {
    val unit = column.displayUnit.orEmpty()
    val shown = units.value(value, unit)?.takeIf { it.isFinite() } ?: return NO_VALUE
    column.datastream.classes.takeIf { it.isNotEmpty() }?.let { _ ->
        return column.datastream.classOf(shown.toLong())?.takeUnless { it.nodata }?.name ?: NO_VALUE
    }
    return units.format(value!!, unit, column.displayStep, AppProfile.decimalsOf(column.displayStep))
}

/** The ARGB of a `#RRGGBB` colour hint, or null for any other text. */
internal fun hintColor(hint: String?): Color? {
    val hex = hint?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * The hero of a thing without a family hero. The first primary column now, at the profile's display
 * step, with its label and unit. A published scale adds its step name beside the step colour, so
 * colour is never the only carrier. While `INFO` is pending it waits quietly. A thing whose primary
 * columns are all hidden or missing says it has no main reading.
 */
@Composable
internal fun GenericMetricHero(state: TodayUiState, modifier: Modifier = Modifier) {
    val receivedAt = state.snapshot.liveAtEpochMs
    val fresh = rememberIsFresh(receivedAt)
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    val quiet = onCard.copy(alpha = 0.85f)
    val column = state.capabilities.thing?.let(::heroColumn)
    BrandCard(modifier = modifier, tone = CardTone.HERO) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardHeader(stringResource(if (fresh) Res.string.now_right_now else Res.string.now_last_reading), modifier = Modifier.weight(1f), color = quiet)
            FreshnessLabel(receivedAt, color = quiet)
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.liveSupport == LiveSupport.PENDING -> BodyText(stringResource(Res.string.now_waiting), style = MaterialTheme.typography.headlineSmall)
            state.liveSupport == LiveSupport.UNSUPPORTED -> BodyText(stringResource(Res.string.env_unsupported), style = MaterialTheme.typography.bodyMedium)
            column == null -> BodyText(stringResource(Res.string.now_no_main_reading), style = MaterialTheme.typography.headlineSmall)
            else -> HeroValue(column, state.reading, fresh, onCard, quiet)
        }
    }
}

@Composable
internal fun HeroValue(column: ThingDatastream, reading: LiveReading?, fresh: Boolean, onCard: Color, quiet: Color) {
    val raw = reading?.value(column.name)
    val units = LocalTodayUnits.current
    // Label, value and unit read as one element ("Soil moisture, 31, %").
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        FitText(column.localLabel(), style = MaterialTheme.typography.titleMedium, color = quiet)
        FitText(formatReading(raw, column, units), style = MaterialTheme.typography.displayLarge, color = if (fresh) onCard else quiet, maxLines = 1)
        column.displayUnit?.takeIf { it.isNotEmpty() && column.datastream.classes.isEmpty() }?.let {
            FitText(units.unit(it), style = MaterialTheme.typography.titleMedium, color = quiet, maxLines = 1)
        }
    }
    val step = raw?.let { column.scale?.stepOf(it) }
    if (step != null) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(hintColor(step.colorHint) ?: quiet, size = 12)
            FitText(step.label.get(Locale.current.language), style = MaterialTheme.typography.titleSmall, color = onCard)
        }
    }
    if (reading == null) {
        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(Res.string.now_waiting), style = MaterialTheme.typography.bodySmall, color = quiet)
    }
}

/** Detailed level: the thing's other readings, from [otherReadings], one line each. */
@Composable
internal fun MetricsCard(state: TodayUiState, modifier: Modifier = Modifier) {
    val thing = state.capabilities.thing ?: return
    val units = LocalTodayUnits.current
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.metrics_title), modifier = Modifier.weight(1f), heading = true)
            FreshnessLabel(state.snapshot.liveAtEpochMs)
        }
        Spacer(Modifier.height(8.dp))
        otherReadings(thing).forEach { column ->
            val unit = column.displayUnit?.takeIf { it.isNotEmpty() && column.datastream.classes.isEmpty() }?.let(units::unit)
            val value = formatReading(state.reading?.value(column.name), column, units)
            StatLine(column.localLabel(), if (unit == null || value == NO_VALUE) value else "$value $unit")
        }
    }
}
