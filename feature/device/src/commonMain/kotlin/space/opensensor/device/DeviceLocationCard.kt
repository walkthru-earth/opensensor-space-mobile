package space.opensensor.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.PillButton
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.action_cancel
import space.opensensor.device.resources.location_clear
import space.opensensor.device.resources.location_configure
import space.opensensor.device.resources.location_country
import space.opensensor.device.resources.location_country_info
import space.opensensor.device.resources.location_failed
import space.opensensor.device.resources.location_fine_warning
import space.opensensor.device.resources.location_fix_failed
import space.opensensor.device.resources.location_fresh_required
import space.opensensor.device.resources.location_info
import space.opensensor.device.resources.location_locating
import space.opensensor.device.resources.location_not_confirmed
import space.opensensor.device.resources.location_permission
import space.opensensor.device.resources.location_permission_settings
import space.opensensor.device.resources.location_placement
import space.opensensor.device.resources.location_precision
import space.opensensor.device.resources.location_precision_info
import space.opensensor.device.resources.location_save
import space.opensensor.device.resources.location_saved
import space.opensensor.device.resources.location_set
import space.opensensor.device.resources.location_title
import space.opensensor.device.resources.location_unset
import space.opensensor.device.resources.location_unsupported
import space.opensensor.device.resources.location_use_phone
import space.opensensor.permissions.AccessDialogTexts
import space.opensensor.permissions.AccessDialogs

@Composable
internal fun DeviceLocationCard(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    val title = stringResource(Res.string.location_title)
    BrandCard {
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.location_info)))
        DeviceLocationForm(state, onAction)
    }
}

/**
 * The sensor location, its Configure button and the dialog that saves it. Shared by the Device
 * settings card and the setup wizard, so both send the same `location.*` keys. Approximate location
 * is asked for only from Use phone location.
 */
@Composable
fun DeviceLocationForm(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    val loc = state.location
    val draft = loc.draft
    var open by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.location_title)
    Column {
        Text(if (draft.cell.isEmpty()) stringResource(Res.string.location_unset) else stringResource(Res.string.location_set))
        if (state.level.detailed && draft.cell.isNotEmpty()) Text(draft.cell)
        if (loc.supported == false) Text(stringResource(Res.string.location_unsupported))
        PillButton(stringResource(Res.string.location_configure), onClick = { open = true }, enabled = state.canEdit && loc.supported == true && !loc.working)
        loc.result?.let { result ->
            Text(
                stringResource(
                    when (result) {
                        DeviceLocationResult.SAVED -> Res.string.location_saved
                        DeviceLocationResult.FAILED -> Res.string.location_failed
                        DeviceLocationResult.NOT_CONFIRMED -> Res.string.location_not_confirmed
                    }
                )
            )
        }
    }
    if (!open) return
    val request = rememberDeviceLocationRequest()
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var fixFailed by remember { mutableStateOf(false) }
    var precisionMenu by remember { mutableStateOf(false) }
    AccessDialogs(
        request.access,
        AccessDialogTexts(
            rationaleTitle = title,
            rationaleBody = stringResource(Res.string.location_permission),
            settingsTitle = title,
            settingsBody = stringResource(Res.string.location_permission_settings),
        )
    )
    AlertDialog(
        onDismissRequest = { if (!loc.working) open = false },
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(Res.string.location_placement))
                TextButton(onClick = { precisionMenu = true }, enabled = !loc.working && !locating) {
                    Text(stringResource(Res.string.location_precision, draft.resolution))
                    DropdownMenu(expanded = precisionMenu, onDismissRequest = { precisionMenu = false }) {
                        for (resolution in 0..15) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.location_precision, resolution)) },
                                onClick = { precisionMenu = false; onAction(DeviceConfigAction.LocationResolution(resolution)) },
                            )
                        }
                    }
                }
                Text(stringResource(Res.string.location_precision_info))
                if (draft.resolution > DeviceLocationDraft.DEFAULT_RESOLUTION) Text(stringResource(Res.string.location_fine_warning))
                OutlinedTextField(
                    value = draft.country,
                    onValueChange = { if (it.length <= 2) onAction(DeviceConfigAction.LocationCountry(it)) },
                    label = { Text(stringResource(Res.string.location_country)) },
                    // One letter is the only invalid length the field allows, so the hint turns into the error.
                    isError = draft.country.length == 1,
                    supportingText = {
                        Text(stringResource(Res.string.location_country_info), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loc.working,
                )
                if (draft.cell.isNotEmpty()) Text(draft.cell)
                if (draft.needsFix) Text(stringResource(Res.string.location_fresh_required))
                if (fixFailed) Text(stringResource(Res.string.location_fix_failed))
                TextButton(enabled = state.canEdit && !loc.working && !locating, onClick = {
                    val resolution = draft.resolution
                    request.access.run(onDeclined = { fixFailed = true }) {
                        locating = true
                        fixFailed = false
                        scope.launch {
                            try {
                                val cell = withTimeoutOrNull(25_000) { request.cell(resolution) }
                                if (cell == null) fixFailed = true else onAction(DeviceConfigAction.LocationCell(cell, resolution))
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { fixFailed = true } finally { locating = false }
                        }
                    }
                }) { Text(stringResource(if (locating) Res.string.location_locating else Res.string.location_use_phone)) }
                TextButton(enabled = !loc.working && !locating, onClick = { onAction(DeviceConfigAction.ClearLocation) }) {
                    Text(stringResource(Res.string.location_clear))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.canEdit && !state.snapshot.syncRunning && !loc.working && !locating && !draft.needsFix &&
                    (draft.country.isEmpty() || Regex("[A-Z]{2}").matches(draft.country)),
                onClick = { onAction(DeviceConfigAction.SaveLocation); open = false }
            ) {
                Text(stringResource(Res.string.location_save))
            }
        },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !loc.working) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
