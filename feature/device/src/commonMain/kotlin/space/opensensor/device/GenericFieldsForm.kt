package space.opensensor.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.FieldType
import space.opensensor.data.sync.PlanField
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.OutlinePillButton
import space.opensensor.designsystem.PillButton
import space.opensensor.designsystem.SwitchRow
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.board_failed
import space.opensensor.device.resources.board_not_confirmed
import space.opensensor.device.resources.board_range
import space.opensensor.device.resources.board_read_failed
import space.opensensor.device.resources.board_save
import space.opensensor.device.resources.board_saved
import space.opensensor.device.resources.board_title
import space.opensensor.device.resources.site_retry

/** Whether a board form may send now. */
val DeviceConfigUiState.canEditBoard: Boolean get() = canEdit && !snapshot.syncRunning && board.supported == true && !board.working

/**
 * The board keys a template declares for one setup step or settings section, drawn from their
 * types. A toggle is a switch, a whole number and a text are fields, a choice is a row of chips and
 * an action is a button that opens its confirmation. Typing only drafts. The wizard's Next, or the
 * caller's Save, sends the changed values with [DeviceConfigController.saveBoardAndWait]. The label
 * is the template's, in the phone's language, else the key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenericFieldsForm(state: DeviceConfigUiState, fields: List<PlanField>, onAction: (DeviceConfigAction) -> Unit) {
    val board = state.board
    val enabled = state.canEditBoard
    val language = Locale.current.language
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { planField ->
            val field = planField.field
            val label = planField.label?.get(language) ?: field.key
            val value = board.value(field.key)
            val edit: (String) -> Unit = { onAction(DeviceConfigAction.BoardValue(field.key, it)) }
            when (val type = field.type) {
                FieldType.Toggle -> SwitchRow(label, checked = value == "1", onCheckedChange = { edit(if (it) "1" else "0") }, enabled = enabled)
                FieldType.Action -> OutlinePillButton(label, onClick = { onAction(DeviceConfigAction.BoardAction(planField)) }, enabled = state.canDisrupt && board.supported == true)
                is FieldType.Choice -> {
                    Text(label)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        type.values.forEach { option ->
                            FilterChip(selected = value == option, onClick = { edit(option) }, label = { Text(option) }, enabled = enabled)
                        }
                    }
                }
                is FieldType.IntBetween -> OutlinedTextField(
                    value = value,
                    onValueChange = { typed -> edit(typed.filter { it.isDigit() || it == '-' }) },
                    label = { Text(label) },
                    supportingText = { Text(stringResource(Res.string.board_range, type.min, type.max)) },
                    isError = value.isNotEmpty() && value.toIntOrNull()?.let { it in type.min..type.max } != true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                is FieldType.Text -> OutlinedTextField(
                    value = value,
                    onValueChange = { typed -> edit(typed.replace(LINE_BREAK, " ")) },
                    label = { Text(label) },
                    isError = value.encodeToByteArray().size.let { it < type.minBytes || it > type.maxBytes },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                // ConfigFields.merge never offers a board key of another type.
                else -> Unit
            }
        }
        when (board.result) {
            null -> Unit
            DeviceBoardResult.READ_FAILED -> {
                Text(stringResource(Res.string.board_read_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(enabled = state.ready && state.isV2 && !board.working, onClick = { onAction(DeviceConfigAction.Refresh) }) {
                    Text(stringResource(Res.string.site_retry))
                }
            }
            DeviceBoardResult.SAVED -> Text(stringResource(Res.string.board_saved), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            DeviceBoardResult.NOT_CONFIRMED -> Text(stringResource(Res.string.board_not_confirmed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            DeviceBoardResult.FAILED -> Text(stringResource(Res.string.board_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** One board settings section on Device settings, its declared keys and a Save for the changed ones. */
@Composable
fun BoardSettingsCard(state: DeviceConfigUiState, fields: List<PlanField>, onAction: (DeviceConfigAction) -> Unit, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        CardHeader(stringResource(Res.string.board_title))
        Spacer(Modifier.height(8.dp))
        GenericFieldsForm(state, fields, onAction)
        if (fields.any { it.field.type != FieldType.Action }) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                stringResource(Res.string.board_save),
                onClick = { onAction(DeviceConfigAction.SaveBoard(fields)) },
                enabled = state.canEditBoard && state.board.changes(fields).isNotEmpty(),
            )
        }
    }
}

private val LINE_BREAK = Regex("\r\n|[\r\n]")
