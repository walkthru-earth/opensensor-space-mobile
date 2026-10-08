package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.protocol.LiveProfile
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.Eyebrow

/**
 * Detailed level: the board's own temperature and the battery. Millivolts and USB bus voltage are
 * Developer facts.
 */
@Composable
fun BoardCard(live: LiveSample?, profile: LiveProfile?, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                BigValue(
                    temperatureMetric(profile?.temperature).label,
                    if (profile != null) fmt(live?.t) else "—",
                    temperatureMetric(profile?.temperature).unit,
                )
                Text(
                    stringResource(
                        when (profile?.temperature) {
                            TemperatureSource.BOARD -> R.string.board_temp
                            TemperatureSource.AMBIENT -> R.string.board_ambient_note
                            null -> R.string.board_temp_unsupported
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Eyebrow(Metrics.BATTERY.label, uppercase = false)
                Row(verticalAlignment = Alignment.Bottom) {
                    Icon(
                        if (live?.chg == 1) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(live?.pct?.let { "$it" } ?: "—", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(4.dp))
                    Text(Metrics.BATTERY.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 7.dp))
                }
                val charging = live?.chg == 1
                val detail = buildList {
                    if (charging) add(stringResource(R.string.board_charging))
                    if (level.developer) {
                        live?.bat?.let { add(stringResource(R.string.board_millivolts, it)) }
                        live?.vbus?.takeIf { it > 0 }?.let { add(stringResource(R.string.board_usb_millivolts, it)) }
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(detail.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
