package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.SensorLabel
import earth.walkthru.aqsync.data.local.database.SensorInstallation
import earth.walkthru.aqsync.data.protocol.PlantowerBatchCandidate
import earth.walkthru.aqsync.ui.device.DeviceUiState
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.PillButton
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.WarningNote

/** The result of one barcode scan, delivered by the route. [id] makes a repeated scan distinct. */
data class StickerScan(val value: String?, val error: String?, val id: Long)

/**
 * The fitted sensor module, for boards with `sensorIdentity`. Sticker entry is a physical
 * installation claim. The board must acknowledge it before Room records it. The barcode scanner is
 * launched by the route ([onScan]) and its result arrives as [scan].
 */
@Composable
fun SensorIdentityCard(
    state: DeviceUiState,
    installation: SensorInstallation?,
    historyCount: Int,
    scan: StickerScan?,
    onScan: () -> Unit,
    onSave: (sticker: String, batchEstimate: Boolean, source: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boardSensor = state.config?.config?.sensor
    val level = state.detailLevel
    var vendorMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var serial by rememberSaveable(boardSensor?.serial) { mutableStateOf(boardSensor?.serial.orEmpty()) }
    var source by rememberSaveable(boardSensor?.serial) { mutableStateOf("manual") }
    var useBatch by rememberSaveable(boardSensor?.serial, boardSensor?.batchCandidate) { mutableStateOf(boardSensor?.batchCandidate == 1) }
    LaunchedEffect(scan?.id) {
        scan?.value?.let { serial = it.trim(); source = "barcode" }
    }
    val label = SensorLabel.parse(serial)
    val matchesInstalledModel = label?.model == "PMS5003T"
    val knownBatch = matchesInstalledModel && label?.batch == PlantowerBatchCandidate.BATCH
    val effectiveBatch = useBatch && knownBatch
    val editable = state.canEdit && !state.syncRunning

    BrandCard(modifier = modifier) {
        val title = stringResource(R.string.sensor_title)
        CardHeader(
            title,
            info = InfoContent(
                title,
                stringResource(R.string.sensor_check_model),
                stringResource(R.string.sensor_estimate_explain, PlantowerBatchCandidate.FACTOR.toString()),
            ),
        )
        Text(stringResource(R.string.sensor_installed), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(stringResource(R.string.sensor_vendor), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { vendorMenu = true }, shape = FloatingControl.SmallShape) { Text("Plantower") }
                DropdownMenu(expanded = vendorMenu, onDismissRequest = { vendorMenu = false }) {
                    DropdownMenuItem(text = { Text("Plantower") }, onClick = { vendorMenu = false })
                }
            }
            Column {
                Text(stringResource(R.string.sensor_model), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { modelMenu = true }, shape = FloatingControl.SmallShape) { Text("PMS5003T") }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sensor_model_supported, "PMS5003T")) }, onClick = { modelMenu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sensor_model_unsupported, "PMS5003")) }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text(stringResource(R.string.sensor_model_unsupported, "PMSA003")) }, onClick = {}, enabled = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinePillButton(stringResource(R.string.sensor_scan), onClick = onScan, enabled = editable)
            Text(stringResource(R.string.sensor_or_enter), style = MaterialTheme.typography.bodySmall)
        }
        scan?.error?.takeIf { scan.value == null }?.let { WarningNote(it) }
        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it; source = "manual" },
            label = { Text(stringResource(R.string.sensor_sticker)) },
            placeholder = { Text("PMS5003T-202604081332") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
            isError = serial.isNotBlank() && !matchesInstalledModel,
        )
        if (label != null) {
            StatLine(stringResource(R.string.sensor_batch), label.batch)
        } else if (serial.isNotBlank()) {
            WarningNote(stringResource(R.string.sensor_sticker_invalid))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sensor_store_estimate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = effectiveBatch, onCheckedChange = { useBatch = it }, enabled = editable && knownBatch)
        }
        if (!knownBatch && label != null) InfoNote(stringResource(R.string.sensor_estimate_none))
        Spacer(Modifier.height(10.dp))
        PillButton(
            stringResource(R.string.sensor_save),
            onClick = { onSave(serial, effectiveBatch, source) },
            enabled = editable && matchesInstalledModel,
        )
        Spacer(Modifier.height(8.dp))
        StatLine(stringResource(R.string.sensor_device_sticker), boardSensor?.serial ?: stringResource(R.string.value_not_set))
        StatLine(stringResource(R.string.sensor_estimate_state), stringResource(if (boardSensor?.batchCandidate == 1) R.string.sensor_estimate_on else R.string.value_off))
        installation?.let {
            if (level.detailed) {
                StatLine(stringResource(R.string.sensor_phone_record), it.serial ?: stringResource(R.string.value_unknown))
                StatLine(stringResource(R.string.sensor_records), historyCount.toString())
            }
            if (boardSensor != null && boardSensor.serial != it.serial) {
                WarningNote(stringResource(R.string.sensor_mismatch))
            }
        }
    }
}
