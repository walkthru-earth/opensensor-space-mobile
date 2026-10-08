package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.protocol.LiveProfile
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.CardTone
import earth.walkthru.aqsync.ui.components.Eyebrow

/** The registry metric that a reviewed profile's LIVE `t` field measures. */
fun temperatureMetric(source: TemperatureSource?): MetricSpec =
    if (source == TemperatureSource.AMBIENT) Metrics.AMBIENT_TEMP else Metrics.BOARD_TEMP

/**
 * Temperature (and humidity where the reviewed profile has it). The hero of Today on a device
 * without a particle sensor, and a second card under the air on an ambient-sensing board. An
 * unknown profile says that live values are not supported yet, never guesses.
 */
@Composable
fun EnvironmentCard(profile: LiveProfile?, live: LiveSample?, receivedAtEpochMs: Long?, hero: Boolean, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier, tone = if (hero) CardTone.SOFT else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.env_title), modifier = Modifier.weight(1f))
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(12.dp))
        if (profile == null) {
            Text(stringResource(R.string.env_unsupported), style = MaterialTheme.typography.bodyMedium)
            return@BrandCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigValue(
                temperatureMetric(profile.temperature).label,
                fmt(live?.t),
                temperatureMetric(profile.temperature).unit,
                Modifier.weight(1f),
            )
            if (profile.humidity) BigValue(Metrics.HUMIDITY.label, fmt(live?.rh), Metrics.HUMIDITY.unit, Modifier.weight(1f))
        }
        if (live == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.now_waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
