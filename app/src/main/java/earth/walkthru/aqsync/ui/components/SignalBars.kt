package earth.walkthru.aqsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.opensensor.designsystem.FloatingControl

/** Signal strength in four bars from an RSSI (-50 dBm is excellent, -90 barely there). */
fun signalBars(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -67 -> 3
    rssi >= -80 -> 2
    else -> 1
}

/**
 * Four signal bars. The dBm number is a Developer fact ([showDbm]); the bars carry the meaning for
 * everyone and are announced as "signal N of 4". On a selected (Signal blue) row pass [onSelected]
 * so the bars and the number switch to ink.
 */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier, showDbm: Boolean = false, onSelected: Boolean = false) {
    val bars = signalBars(rssi)
    val description = stringResource(R.string.signal_bars, bars)
    Column(modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..4) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        onSelected && i <= bars -> FloatingControl.OnSelected
                        onSelected -> FloatingControl.OnSelected.copy(alpha = 0.3f)
                        i <= bars -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    modifier = Modifier.width(5.dp).height((6 + i * 4).dp),
                ) {}
            }
        }
        if (showDbm) Text("$rssi dBm", style = MaterialTheme.typography.labelSmall, color = if (onSelected) FloatingControl.OnSelected else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
