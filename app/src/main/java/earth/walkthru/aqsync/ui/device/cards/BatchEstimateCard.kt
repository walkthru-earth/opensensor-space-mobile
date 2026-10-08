package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PlantowerBatchCandidate
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.CardHeader
import earth.walkthru.aqsync.ui.components.InfoContent

/**
 * Detailed level, on the reviewed Waveshare v2 dictionary after the board acknowledged the opt-in:
 * the unvalidated batch estimate. Only a fresh valid count received after that acknowledgement is
 * used. The main reading stays raw PM2.5.
 */
@Composable
fun BatchEstimateCard(live: LiveSample?, receivedAtEpochMs: Long?, config: ConfigSnapshot?, modifier: Modifier = Modifier) {
    val fresh = rememberIsFresh(receivedAtEpochMs) && receivedAtEpochMs != null && receivedAtEpochMs >= (config?.atEpochMs ?: Long.MAX_VALUE)
    val estimate = if (fresh) {
        PlantowerBatchCandidate.estimate(config?.config?.sensor?.serial, config?.config?.sensor?.batchCandidate, live?.pms, live?.n03)
    } else {
        null
    }
    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.batch_title)
        CardHeader(title, info = InfoContent(title, stringResource(R.string.batch_explain, PlantowerBatchCandidate.FACTOR.toString())))
        Text(
            if (estimate != null) stringResource(R.string.batch_value, fmt(estimate)) else stringResource(R.string.batch_waiting),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
