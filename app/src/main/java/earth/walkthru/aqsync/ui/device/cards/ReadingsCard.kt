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
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.titleText
import earth.walkthru.opensensor.designsystem.BigValue
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.Eyebrow

/**
 * Detailed level: the other particle sizes beside the hero's PM2.5. Developer adds the CF=1 raw
 * values, which are a sensor-internal correction and easy to misread.
 */
@Composable
fun ReadingsCard(live: LiveSample?, receivedAtEpochMs: Long?, level: DetailLevel, modifier: Modifier = Modifier) {
    val showPm = live?.pmValid == true
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.readings_title), modifier = Modifier.weight(1f))
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigValue(Metrics.PM10.label, if (showPm) fmt(live?.pm10) else NO_VALUE, Metrics.PM10.unit, Modifier.weight(1f))
            BigValue(Metrics.PM1.label, if (showPm) fmt(live?.pm1) else NO_VALUE, Metrics.PM10.unit, Modifier.weight(1f))
        }
        if (!showPm) {
            val status = live?.pmsStatus
            Text(
                when {
                    live == null -> stringResource(R.string.now_waiting)
                    status == null || status == PmsStatus.VALID -> stringResource(R.string.readings_absent)
                    else -> status.titleText()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (level.developer && showPm && (live?.c25 != null || live?.c1 != null || live?.c10 != null)) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.readings_cf1, fmt(live?.c1), fmt(live?.c25), fmt(live?.c10)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
