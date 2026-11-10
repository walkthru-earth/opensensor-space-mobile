package space.opensensor.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.OutlinePillButton
import space.opensensor.designsystem.PillButton
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.action_cancel
import space.opensensor.device.resources.location_area_detail
import space.opensensor.device.resources.location_area_km2
import space.opensensor.device.resources.location_area_m2
import space.opensensor.device.resources.location_cell_change
import space.opensensor.device.resources.location_clear
import space.opensensor.device.resources.location_configure
import space.opensensor.device.resources.location_country
import space.opensensor.device.resources.location_country_error
import space.opensensor.device.resources.location_country_info
import space.opensensor.device.resources.location_failed
import space.opensensor.device.resources.location_fix_failed
import space.opensensor.device.resources.location_fresh_required
import space.opensensor.device.resources.location_info
import space.opensensor.device.resources.location_locating
import space.opensensor.device.resources.location_mobility_fixed
import space.opensensor.device.resources.location_mobility_info
import space.opensensor.device.resources.location_mobility_moving
import space.opensensor.device.resources.location_mobility_title
import space.opensensor.device.resources.location_moving_gnss
import space.opensensor.device.resources.location_moving_no_gnss
import space.opensensor.device.resources.location_not_confirmed
import space.opensensor.device.resources.location_permission
import space.opensensor.device.resources.location_permission_settings
import space.opensensor.device.resources.location_placement
import space.opensensor.device.resources.location_precision_info
import space.opensensor.device.resources.location_save
import space.opensensor.device.resources.location_saved
import space.opensensor.device.resources.location_set
import space.opensensor.device.resources.location_site_title
import space.opensensor.device.resources.location_stop
import space.opensensor.device.resources.location_title
import space.opensensor.device.resources.location_unset
import space.opensensor.device.resources.location_unsupported
import space.opensensor.device.resources.location_use_gnss
import space.opensensor.device.resources.location_use_phone
import space.opensensor.device.resources.site_info
import space.opensensor.device.resources.site_title
import space.opensensor.map.CellFocus
import space.opensensor.map.CellPicker
import space.opensensor.map.PickerLocateButton
import space.opensensor.permissions.AccessDialogTexts
import space.opensensor.permissions.AccessDialogs

@Composable
internal fun DeviceLocationCard(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    val siteShown = state.site.supported == true
    val title = stringResource(if (siteShown) Res.string.location_site_title else Res.string.location_title)
    BrandCard {
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.location_info)))
        DeviceLocationForm(state, onAction)
        // A board without the owner site keys answers page 5 with an error. Its fields stay hidden.
        if (siteShown) {
            val siteTitle = stringResource(Res.string.site_title)
            Spacer(Modifier.height(16.dp))
            CardHeader(siteTitle, info = InfoContent(siteTitle, stringResource(Res.string.site_info)))
            DeviceSiteForm(state, onAction, showSave = true)
        } else if (state.site.supported == null && state.site.result == DeviceSiteResult.READ_FAILED) {
            // Page 5 or 6 could not be read, so support is still unknown. Say so and offer a new read.
            Spacer(Modifier.height(16.dp))
            SiteReadFailed(state, onAction)
        }
    }
}

/**
 * The sensor location, its Choose area button and the map that saves it. Shared by the Device
 * settings card and the setup wizard, so both send the same `location.*` keys. The map picks one
 * resolution 8 cell. Approximate phone location is asked for only from its locate button, and its
 * cell only moves the map.
 */
@Composable
fun DeviceLocationForm(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    val loc = state.location
    val draft = loc.draft
    // Survives rotation. Only whether the map is open, never a place or a cell.
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        // Only firmware that reports `thing.mobility` is offered the choice, older firmware rejects the key.
        loc.mobility?.let { mobility -> MobilityChoice(mobility, enabled = state.canEdit && !state.snapshot.syncRunning && !loc.working, onAction) }
        if (loc.mobility == ThingMobility.MOVING) {
            // A moving Thing takes no configured cell. Its own receiver places each row, or nothing does.
            Text(stringResource(if (loc.movingWithoutGnss) Res.string.location_moving_no_gnss else Res.string.location_moving_gnss))
        } else {
            Text(if (draft.cell.isEmpty()) stringResource(Res.string.location_unset) else stringResource(Res.string.location_set))
            // The cell id is for people who read H3. Simple shows the words only.
            if (state.level.detailed && draft.cell.isNotEmpty()) Text(draft.cell)
            if (loc.supported == false) Text(stringResource(Res.string.location_unsupported))
            PillButton(stringResource(Res.string.location_configure), onClick = { open = true }, enabled = state.canEdit && loc.supported == true && !loc.working)
        }
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
    if (open) LocationPickerDialog(state, onAction, onClose = { open = false })
}

/** Fixed or moving, sent at once as `thing.mobility` on a tap (#263). Signal blue marks only the stored one. */
@Composable
private fun MobilityChoice(stored: ThingMobility, enabled: Boolean, onAction: (DeviceConfigAction) -> Unit) {
    val title = stringResource(Res.string.location_mobility_title)
    CardHeader(title, info = InfoContent(title, stringResource(Res.string.location_mobility_info)))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        MOBILITIES.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == stored,
                onClick = { if (option != stored) onAction(DeviceConfigAction.SetMobility(option)) },
                enabled = enabled,
                shape = FloatingControl.segmentShape(i, MOBILITIES.size),
                colors = FloatingControl.segmentedColors(),
                label = {
                    FitText(
                        stringResource(
                            when (option) {
                                ThingMobility.FIXED -> Res.string.location_mobility_fixed
                                ThingMobility.MOVING -> Res.string.location_mobility_moving
                            }
                        )
                    )
                },
            )
        }
    }
}

private val MOBILITIES = listOf(ThingMobility.FIXED, ThingMobility.MOVING)

/**
 * The full screen area map. A tap on a cell drafts it, Save sends it. The phone fix becomes a
 * [CellFocus] that moves the camera and never drafts a cell by itself.
 */
@Composable
private fun LocationPickerDialog(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit, onClose: () -> Unit) {
    val loc = state.location
    val draft = loc.draft
    val title = stringResource(Res.string.location_title)
    val request = rememberDeviceLocationRequest()
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var fixJob by remember { mutableStateOf<Job?>(null) }
    var fixFailed by remember { mutableStateOf(false) }
    // The board's own fresh GNSS cell comes first. The map opens on it, and the owner still confirms it.
    var focus by remember { mutableStateOf(loc.gnssCell?.takeIf { draft.cell.isEmpty() }?.let { CellFocus(it, 1) }) }
    val close = {
        fixJob?.cancel()
        onClose()
    }
    AccessDialogs(
        request.access,
        AccessDialogTexts(
            rationaleTitle = title,
            rationaleBody = stringResource(Res.string.location_permission),
            settingsTitle = title,
            settingsBody = stringResource(Res.string.location_permission_settings),
        )
    )
    val locate = {
        request.access.run(onDeclined = { fixFailed = true }) {
            locating = true
            fixFailed = false
            fixJob = scope.launch {
                try {
                    val cell = withTimeoutOrNull(FIX_TIMEOUT_MS) { request.cell(DeviceLocationDraft.RESOLUTION) }
                    if (cell == null) fixFailed = true else focus = CellFocus(cell, (focus?.serial ?: 0) + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fixFailed = true
                } finally {
                    locating = false
                    fixJob = null
                }
            }
        }
    }
    val canPick = state.canEdit && !loc.working
    val canSave = state.canEdit && !state.snapshot.syncRunning && !loc.working && !locating && !draft.needsFix && countryValid(draft.country)
    Dialog(onDismissRequest = { if (!loc.working) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close, enabled = !loc.working) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_cancel))
                    }
                    FitText(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    InfoButton(InfoContent(title, listOf(stringResource(Res.string.location_info), stringResource(Res.string.location_precision_info))))
                    TextButton(enabled = canSave, onClick = {
                        onAction(DeviceConfigAction.SaveLocation)
                        close()
                    }) { Text(stringResource(Res.string.location_save)) }
                }
                CellPicker(
                    selected = draft.cell.ifEmpty { null },
                    onSelect = { cell -> onAction(DeviceConfigAction.LocationCell(cell, DeviceLocationDraft.RESOLUTION)) },
                    modifier = Modifier.weight(1f),
                    focus = focus,
                    enabled = canPick,
                    controls = {
                        PickerLocateButton(
                            onClick = {
                                if (locating) {
                                    fixJob?.cancel()
                                } else if (canPick) {
                                    locate()
                                }
                            },
                            // While a slow fix runs the same button stops it. Nothing is kept from a stopped fix.
                            contentDescription = stringResource(if (locating) Res.string.location_stop else Res.string.location_use_phone),
                            busy = locating,
                        )
                    },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // One status line, read out as a cell is picked or a fix lands or fails.
                    val status = when {
                        locating -> stringResource(Res.string.location_locating)
                        fixFailed -> stringResource(Res.string.location_fix_failed)
                        draft.needsFix -> stringResource(Res.string.location_fresh_required)
                        draft.cell.isNotEmpty() -> stringResource(Res.string.location_set)
                        else -> stringResource(Res.string.location_placement)
                    }
                    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    // The cell size in words. Detailed adds the H3 resolution and the cell id.
                    Text(areaText(draft.resolution, state.level.detailed), style = MaterialTheme.typography.labelMedium)
                    if (state.level.detailed && draft.cell.isNotEmpty()) Text(draft.cell, style = MaterialTheme.typography.labelMedium)
                    if (loc.cellChanges) InfoNote(stringResource(Res.string.location_cell_change))
                    loc.gnssCell?.takeIf { it != draft.cell }?.let { gnss ->
                        OutlinePillButton(
                            stringResource(Res.string.location_use_gnss),
                            onClick = {
                                onAction(DeviceConfigAction.LocationCell(gnss, DeviceLocationDraft.RESOLUTION))
                                focus = CellFocus(gnss, (focus?.serial ?: 0) + 1)
                            },
                            enabled = canPick && !locating,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft.country,
                            onValueChange = { if (it.length <= 2) onAction(DeviceConfigAction.LocationCountry(it)) },
                            label = { Text(stringResource(Res.string.location_country)) },
                            // The hint turns into the reason when the code cannot be saved, and is read out then.
                            isError = !countryValid(draft.country),
                            supportingText = {
                                Text(
                                    stringResource(if (countryValid(draft.country)) Res.string.location_country_info else Res.string.location_country_error),
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = !loc.working,
                        )
                        TextButton(enabled = canPick && !locating && draft.cell.isNotEmpty(), onClick = { onAction(DeviceConfigAction.ClearLocation) }) {
                            Text(stringResource(Res.string.location_clear))
                        }
                    }
                }
            }
        }
    }
}

private const val FIX_TIMEOUT_MS = 25_000L

/** The area of one cell at [resolution] in words, with the H3 resolution added at [detailed]. */
@Composable
private fun areaText(resolution: Int, detailed: Boolean): String {
    val area = LocationArea.label(resolution)
    val size = stringResource(
        when (area.unit) {
            LocationArea.Unit.KM2 -> Res.string.location_area_km2
            LocationArea.Unit.M2 -> Res.string.location_area_m2
        },
        area.value,
    )
    return if (detailed) stringResource(Res.string.location_area_detail, size, resolution) else size
}
