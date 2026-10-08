package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.Eyebrow

/** Detailed level: particle counts per 0.1 L by size bin. */
@Composable
fun ParticlesCard(live: LiveSample?, modifier: Modifier = Modifier) {
    val counts = listOf(
        ">0.3" to live?.n03,
        ">0.5" to live?.n05,
        ">1" to live?.n1,
        ">2.5" to live?.n25,
        ">5" to live?.n5,
        ">10" to live?.n10,
    )
    BrandCard(modifier = modifier) {
        // Not uppercased. "µ".uppercase() is the Greek capital mu, which once rendered as "(MM)".
        Eyebrow(stringResource(R.string.particles_title), uppercase = false)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            counts.forEach { (label, n) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
