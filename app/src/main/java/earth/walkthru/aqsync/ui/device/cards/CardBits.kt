package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.StatusDot
import earth.walkthru.aqsync.ui.components.agoFormatter
import earth.walkthru.aqsync.ui.components.rememberNowMillis
import earth.walkthru.aqsync.units.formatPm
import java.util.Locale

/** A reading older than this is shown as stale (the device samples every 10 s). */
const val STALE_AFTER_MS = 30_000L

/** Readings print without a trailing `.0` and with one decimal otherwise. */
fun fmt(v: Double?): String = when {
    v == null -> NO_VALUE
    v == Math.floor(v) && v < 1000 -> String.format(Locale.US, "%.0f", v)
    else -> String.format(Locale.US, "%.1f", v)
}

/** PM values, the shared rule both apps use ([formatPm] in `:core:units`). */
fun fmtPm(v: Double): String = formatPm(v)

/**
 * "Updated 5 s ago" with a dot that greys out when the reading is stale. A leaf: it owns the
 * 1 Hz clock so the card around it does not recompose every second.
 */
@Composable
fun FreshnessLabel(receivedAtEpochMs: Long?, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val now = rememberNowMillis()
    val ageMs = receivedAtEpochMs?.let { now - it }
    val stale = ageMs == null || ageMs > STALE_AFTER_MS
    val ago = agoFormatter()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(if (stale) color.copy(alpha = 0.4f) else color, size = 8)
        Text(
            when {
                ageMs == null -> stringResource(R.string.fresh_none)
                stale -> stringResource(R.string.fresh_stale, ago(ageMs))
                else -> stringResource(R.string.fresh_updated, ago(ageMs))
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** A labelled number with its unit, for the secondary measurements. */
@Composable
fun BigValue(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(label, uppercase = false)
        Row {
            Text(value, style = MaterialTheme.typography.displaySmall, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        }
    }
}
