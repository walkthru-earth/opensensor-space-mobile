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
import earth.walkthru.aqsync.ui.components.CardHeader
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.OutlinePillButton
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.device.DeviceUiState

/** Phones bonded to the device, and the confirmed "forget all" action (the route asks first). */
@Composable
fun BondsCard(state: DeviceUiState, onForgetAll: () -> Unit, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.bonds_title)
        CardHeader(title, info = InfoContent(title, stringResource(R.string.bonds_explain)))
        StatLine(stringResource(R.string.bonds_count), state.config?.config?.ble?.bonds?.toString() ?: NO_VALUE)
        Spacer(Modifier.height(10.dp))
        OutlinePillButton(stringResource(R.string.bonds_forget), onClick = onForgetAll, enabled = state.canDisrupt)
    }
}
