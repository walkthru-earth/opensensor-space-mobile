package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.device.DeviceUiState
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.WarningNote

/**
 * Bluetooth pairing mode and PIN. Saving mode `none` and rebooting are confirmed by the route.
 * Pairing settings apply at the next boot, so the card says so and offers the reboot.
 */
@Composable
fun PairingCard(state: DeviceUiState, onSave: (PairMode, String?) -> Unit, onReboot: () -> Unit, modifier: Modifier = Modifier) {
    val ble = state.config?.config?.ble
    val current = ble?.pairMode
    var selected by rememberSaveable(current) { mutableStateOf(current ?: PairMode.RANDOM) }
    var pin by rememberSaveable { mutableStateOf("") }

    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.pairing_title)
        CardHeader(
            title,
            info = InfoContent(
                title,
                stringResource(R.string.info_pairing_body),
                stringResource(R.string.pairing_random),
                stringResource(R.string.pairing_fixed),
                stringResource(R.string.pairing_none),
                stringResource(R.string.pairing_next_boot),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (current != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
            Text(
                when {
                    current != null -> stringResource(R.string.pairing_mode, pairModeLabel(current))
                    state.config == null -> stringResource(R.string.config_not_read)
                    else -> stringResource(R.string.pairing_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ble != null && state.detailLevel.developer) {
            StatLine(
                stringResource(R.string.pairing_display),
                when (ble.display) {
                    1 -> stringResource(R.string.value_yes)
                    0 -> stringResource(R.string.value_no)
                    else -> NO_VALUE
                },
            )
        }
        if (ble?.isPinDefault == true && current == PairMode.FIXED) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(R.string.pairing_default_pin, ConfigPatch.DEFAULT_PIN))
        }
        if (state.config?.rebootRequired == true) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(R.string.pairing_reboot_pending))
        }
        Spacer(Modifier.height(10.dp))
        PairMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = state.canEdit) { selected = mode }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = { selected = mode }, enabled = state.canEdit)
                Text(pairModeLabel(mode), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (selected == PairMode.FIXED) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) pin = v },
                label = { Text(stringResource(if (current == PairMode.FIXED) R.string.pairing_new_pin else R.string.pairing_pin)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = VisualTransformation.None,
                enabled = state.canEdit,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (pin.isNotEmpty() && pin.length != 6) ({ Text(stringResource(R.string.pairing_six_digits)) }) else null,
                isError = pin.isNotEmpty() && pin.length != 6,
            )
        }
        Spacer(Modifier.height(10.dp))
        val pinOk = selected != PairMode.FIXED || pin.length == 6 || (pin.isEmpty() && current == PairMode.FIXED)
        val needsPin = selected == PairMode.FIXED && current != PairMode.FIXED && pin.length != 6
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePillButton(
                stringResource(R.string.pairing_save),
                onClick = { onSave(selected, pin.takeIf { it.length == 6 }) },
                enabled = state.canEdit && pinOk && !needsPin && (selected != current || pin.length == 6),
            )
            // Disabled during a transfer. A REBOOT would kill the download (see DeviceUiState.canDisrupt).
            OutlinePillButton(stringResource(R.string.reboot_title), onClick = onReboot, enabled = state.canDisrupt)
        }
    }
}

/** The short name of a pairing mode. Its explanation lives in the card's info sheet. */
@Composable
fun pairModeLabel(mode: PairMode): String = stringResource(
    when (mode) {
        PairMode.RANDOM -> R.string.pairing_label_random
        PairMode.FIXED -> R.string.pairing_label_fixed
        PairMode.NONE -> R.string.pairing_label_none
    },
)
