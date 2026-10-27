package space.opensensor.ui.device.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.opensensor.app.R
import space.opensensor.data.background.AutoSyncScheduler
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.OutlinePillButton
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.SwitchRow
import space.opensensor.designsystem.WarningNote
import space.opensensor.ui.device.DeviceUiState

/**
 * Per-device opt-in to unattended syncs. The switch flips `known_device.autoSync`. The scheduler
 * registers one 15-minute WorkManager job while any device has it on. Simple states what happens
 * in one line. Detailed spells out each link and what the worker will and will not do.
 */
@Composable
fun AutoSyncCard(
    state: DeviceUiState,
    onToggle: (Boolean) -> Unit,
    onSetupCompanion: () -> Unit,
    onRemoveCompanion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = state.known
    val level = state.detailLevel
    val minutes = AutoSyncScheduler.PERIOD_MINUTES
    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.autosync_title)
        CardHeader(
            title,
            info = InfoContent(
                title,
                listOfNotNull(
                    stringResource(R.string.autosync_explain),
                    stringResource(R.string.autosync_wake_explain).takeIf { state.companionSupported },
                    stringResource(R.string.autosync_wake_ble_drop).takeIf { state.companionSupported },
                ),
            ),
        )
        // The state line names the switch, so the row is one Role.Switch node that reads both.
        SwitchRow(
            pluralStringResource(if (state.autoSync) R.plurals.autosync_on else R.plurals.autosync_off, minutes.toInt(), minutes),
            checked = state.autoSync,
            onCheckedChange = onToggle,
            enabled = known != null,
        )
        if (state.autoSync && !state.autoSyncReachable) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(R.string.autosync_unreachable))
        }
        if (level.detailed) {
            Spacer(Modifier.height(8.dp))
            StatLine(stringResource(R.string.autosync_wifi), stringResource(if (known?.hasLanToken == true) R.string.autosync_wifi_token else R.string.autosync_wifi_no_token))
            StatLine(stringResource(R.string.autosync_bluetooth), stringResource(if (state.bonded) R.string.autosync_bonded else R.string.autosync_not_bonded))
        }
        StatLine(
            stringResource(R.string.autosync_wake),
            stringResource(
                when {
                    !state.companionSupported -> R.string.autosync_wake_unavailable
                    state.companion -> R.string.autosync_wake_on
                    else -> R.string.autosync_wake_off
                },
            ),
        )
        if (state.companionSupported && known != null) {
            Spacer(Modifier.height(10.dp))
            if (state.companion) {
                OutlinePillButton(stringResource(R.string.autosync_wake_turn_off), onClick = onRemoveCompanion)
            } else {
                OutlinePillButton(
                    stringResource(if (state.companionBusy) R.string.autosync_wake_waiting else R.string.autosync_wake_setup),
                    onClick = onSetupCompanion,
                    enabled = !state.companionBusy,
                )
            }
        }
    }
}
