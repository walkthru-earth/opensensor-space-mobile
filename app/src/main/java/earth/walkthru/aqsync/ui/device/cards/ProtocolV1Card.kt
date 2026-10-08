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
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.Eyebrow

/** A protocol v1 device has no configuration surface. File sync works as before. */
@Composable
fun ProtocolV1Card(modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Eyebrow(stringResource(R.string.v1_title))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.v1_body), style = MaterialTheme.typography.bodyMedium)
    }
}
