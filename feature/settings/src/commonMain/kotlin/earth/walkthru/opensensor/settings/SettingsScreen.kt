package earth.walkthru.opensensor.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.MinTouchTarget
import earth.walkthru.opensensor.designsystem.cardSemantics
import earth.walkthru.opensensor.designsystem.listSeparator
import earth.walkthru.opensensor.designsystem.spokenList
import earth.walkthru.opensensor.settings.resources.Res
import earth.walkthru.opensensor.settings.resources.info_privacy
import earth.walkthru.opensensor.settings.resources.info_settings_body1
import earth.walkthru.opensensor.settings.resources.info_settings_body2
import earth.walkthru.opensensor.settings.resources.level_detailed
import earth.walkthru.opensensor.settings.resources.level_developer
import earth.walkthru.opensensor.settings.resources.level_simple
import earth.walkthru.opensensor.settings.resources.settings_about
import earth.walkthru.opensensor.settings.resources.settings_about_body
import earth.walkthru.opensensor.settings.resources.settings_detail_level
import earth.walkthru.opensensor.settings.resources.settings_fonts
import earth.walkthru.opensensor.settings.resources.settings_level_detailed
import earth.walkthru.opensensor.settings.resources.settings_level_developer
import earth.walkthru.opensensor.settings.resources.settings_level_simple
import earth.walkthru.opensensor.settings.resources.settings_more_about
import earth.walkthru.opensensor.settings.resources.settings_public_sensors
import earth.walkthru.opensensor.settings.resources.settings_public_sensors_body
import earth.walkthru.opensensor.settings.resources.settings_storage
import earth.walkthru.opensensor.settings.resources.settings_storage_body
import earth.walkthru.opensensor.settings.resources.settings_title
import earth.walkthru.opensensor.settings.resources.settings_units
import earth.walkthru.opensensor.settings.resources.settings_units_auto
import earth.walkthru.opensensor.settings.resources.storage_apply
import earth.walkthru.opensensor.settings.resources.storage_cancel
import earth.walkthru.opensensor.settings.resources.storage_clear
import earth.walkthru.opensensor.settings.resources.storage_clear_named
import earth.walkthru.opensensor.settings.resources.storage_history_cache
import earth.walkthru.opensensor.settings.resources.storage_map_cache
import earth.walkthru.opensensor.settings.resources.storage_megabytes
import earth.walkthru.opensensor.settings.resources.storage_old_files
import earth.walkthru.opensensor.settings.resources.storage_old_files_all_dates
import earth.walkthru.opensensor.settings.resources.storage_old_files_confirm_body
import earth.walkthru.opensensor.settings.resources.storage_old_files_confirm_title
import earth.walkthru.opensensor.settings.resources.storage_old_files_count
import earth.walkthru.opensensor.settings.resources.storage_old_files_dates
import earth.walkthru.opensensor.settings.resources.storage_old_files_failed
import earth.walkthru.opensensor.settings.resources.storage_old_files_find
import earth.walkthru.opensensor.settings.resources.storage_old_files_find_named
import earth.walkthru.opensensor.settings.resources.storage_old_files_finding
import earth.walkthru.opensensor.settings.resources.storage_old_files_history_later
import earth.walkthru.opensensor.settings.resources.storage_old_files_info1
import earth.walkthru.opensensor.settings.resources.storage_old_files_info2
import earth.walkthru.opensensor.settings.resources.storage_old_files_info3
import earth.walkthru.opensensor.settings.resources.storage_old_files_kept
import earth.walkthru.opensensor.settings.resources.storage_old_files_none
import earth.walkthru.opensensor.settings.resources.storage_old_files_not_removed
import earth.walkthru.opensensor.settings.resources.storage_old_files_pick_dates
import earth.walkthru.opensensor.settings.resources.storage_old_files_remove
import earth.walkthru.opensensor.settings.resources.storage_old_files_remove_failed
import earth.walkthru.opensensor.settings.resources.storage_old_files_remove_named
import earth.walkthru.opensensor.settings.resources.storage_old_files_removed
import earth.walkthru.opensensor.settings.resources.storage_old_files_removed_none
import earth.walkthru.opensensor.settings.resources.storage_old_files_summary
import earth.walkthru.opensensor.settings.resources.storage_old_files_unreadable
import earth.walkthru.opensensor.settings.resources.storage_removed_files
import earth.walkthru.opensensor.settings.resources.storage_removed_files_again
import earth.walkthru.opensensor.settings.resources.storage_removed_files_confirm_body
import earth.walkthru.opensensor.settings.resources.storage_removed_files_confirm_title
import earth.walkthru.opensensor.settings.resources.storage_sensor_files
import earth.walkthru.opensensor.settings.resources.storage_temporary
import earth.walkthru.opensensor.settings.resources.units_auto
import earth.walkthru.opensensor.settings.resources.units_imperial
import earth.walkthru.opensensor.settings.resources.units_metric
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The Settings title, also the tab label on iOS. */
@Composable
fun settingsTitle(): String = stringResource(Res.string.settings_title)

/**
 * The Settings screen for both apps. The detail level, the units, the
 * one switch for Discover network use (AGENTS.md boundary) and About. Everything else the user can
 * change lives on the device and is edited from the device's own screens.
 *
 * [appVersion] is the host's version name. [automaticUnits] is what Automatic resolves to on this
 * phone now (METRIC or IMPERIAL). Device UTC is automatic on every connection.
 * [infoButton] lets the host reuse its own info affordance.
 */
@Composable
fun SettingsScreen(
    controller: SettingsController,
    appVersion: String,
    automaticUnits: UnitSystem,
    modifier: Modifier = Modifier,
    infoButton: @Composable (title: String, body: List<String>, modifier: Modifier) -> Unit = { t, b, m -> SettingsInfoButton(t, b, m) },
) {
    val stored by controller.prefs.collectAsState()
    val colors = MaterialTheme.colorScheme
    val title = settingsTitle()
    val infoBody = listOfNotNull(
        stringResource(Res.string.info_settings_body1),
        stringResource(Res.string.info_settings_body2),
    )
    Column(modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            title = { Text(title, Modifier.semantics { heading() }) },
            actions = { infoButton(title, infoBody, Modifier) },
        )
        // Nothing until the store is read once, so no control shows a default it might not hold.
        val prefs = stored ?: return@Column
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                Choice(
                    label = stringResource(Res.string.settings_detail_level),
                    options = DetailLevel.entries,
                    selected = prefs.detailLevel,
                    optionLabel = { stringResource(levelLabel(it)) },
                    onSelect = controller::setDetailLevel,
                )
                Caption(stringResource(levelCaption(prefs.detailLevel)))
            }
            SettingsCard {
                Choice(
                    label = stringResource(Res.string.settings_units),
                    options = UnitSystem.entries,
                    selected = prefs.unitSystem,
                    optionLabel = { stringResource(unitLabel(it)) },
                    onSelect = controller::setUnitSystem,
                )
                if (prefs.unitSystem == UnitSystem.AUTO) {
                    Caption(stringResource(Res.string.settings_units_auto, stringResource(unitLabel(automaticUnits))))
                }
            }
            SettingsCard {
                PublicSensorsRow(prefs.showPublicSensors, controller::setShowPublicSensors)
                Caption(stringResource(Res.string.settings_public_sensors_body), top = 0.dp)
            }
            if (controller.hasStorage) StorageCard(controller, infoButton)
            About(appVersion)
        }
    }
}

/** A titled single choice row. Signal blue marks only the selected segment. */
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = FloatingControl.segmentShape(i, options.size),
                colors = FloatingControl.segmentedColors(),
                label = { Text(optionLabel(option), maxLines = 1) },
            )
        }
    }
}

/** The whole row toggles and reads as one switch with its state, with a touch target of at least [MinTouchTarget]. */
@Composable
private fun PublicSensorsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = on, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.settings_public_sensors), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FloatingControl.Selected,
                checkedThumbColor = FloatingControl.OnSelected,
                checkedBorderColor = FloatingControl.Selected,
                uncheckedTrackColor = colors.surface,
                uncheckedThumbColor = colors.outline,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

/** Usage per storage class, clear actions for the two rebuildable caches (#61), and the old format cleanup. */
@Composable
private fun StorageCard(controller: SettingsController, infoButton: @Composable (title: String, body: List<String>, modifier: Modifier) -> Unit) {
    val usage by controller.storageUsage.collectAsState()
    val clearing by controller.clearing.collectAsState()
    // Measured each time the card shows, so a sync or a clear elsewhere is reflected.
    LaunchedEffect(controller) { controller.refreshStorage() }
    SettingsCard {
        Text(stringResource(Res.string.settings_storage), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val sizes = usage ?: return@SettingsCard
        StorageRow(stringResource(Res.string.storage_sensor_files), sizes.sensorFilesBytes)
        StorageRow(stringResource(Res.string.storage_history_cache), sizes.historyCacheBytes, !clearing, controller::clearHistoryCache)
        StorageRow(stringResource(Res.string.storage_map_cache), sizes.mapCacheBytes, !clearing, controller::clearMapCache)
        StorageRow(stringResource(Res.string.storage_temporary), sizes.temporaryBytes)
        OldFilesRow(controller, !clearing, infoButton)
        RemovedFilesRow(controller, !clearing)
        Caption(stringResource(Res.string.settings_storage_body), top = 4.dp)
    }
}

/**
 * The phone only cleanup of old format sensor files. Find runs a dry run over the chosen UTC dates,
 * Remove asks once and then removes what the dry run counted. Nothing reaches the device.
 */
@Composable
private fun OldFilesRow(
    controller: SettingsController,
    enabled: Boolean,
    infoButton: @Composable (title: String, body: List<String>, modifier: Modifier) -> Unit,
) {
    val state by controller.oldFiles.collectAsState()
    val range by controller.oldFilesRange.collectAsState()
    val archiveDays by controller.archiveDays.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var pickDates by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(Res.string.storage_old_files)
    val info = listOf(
        stringResource(Res.string.storage_old_files_info1),
        stringResource(Res.string.storage_old_files_info2),
        stringResource(Res.string.storage_old_files_info3),
    )
    val found = (state as? OldFilesState.Found)?.plan?.takeIf { it.files > 0 }
    val dates = range?.let { stringResource(Res.string.storage_old_files_dates, isoDate(it.fromEpochDay), isoDate(it.toEpochDay)) } ?: stringResource(Res.string.storage_old_files_all_dates)
    val pickDatesLabel = stringResource(Res.string.storage_old_files_pick_dates)
    val findLabel = stringResource(Res.string.storage_old_files_find_named)
    val removeLabel = stringResource(Res.string.storage_old_files_remove_named)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The name and its status lines read as one sentence, a target of at least MinTouchTarget. The
        // info button keeps its own focus stop.
        Column(Modifier.weight(1f).heightIn(min = MinTouchTarget).cardSemantics(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                infoButton(label, info, Modifier)
            }
            for (line in oldFilesStatus(state)) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (found != null) {
            TextButton(onClick = { confirm = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = removeLabel }) {
                Text(stringResource(Res.string.storage_old_files_remove))
            }
        } else {
            TextButton(onClick = controller::findOldFiles, enabled = enabled && !state.busy, modifier = Modifier.semantics { contentDescription = findLabel }) {
                Text(stringResource(Res.string.storage_old_files_find))
            }
        }
    }
    // Read as the dates it shows, then what it does. The visible words come first, so Voice Control
    // and Voice Access find it by them.
    val datesName = spokenList(listOf(dates, pickDatesLabel), listSeparator())
    TextButton(
        onClick = { pickDates = true },
        enabled = enabled && !state.busy,
        modifier = Modifier.semantics { contentDescription = datesName },
    ) {
        Text(dates)
    }
    if (confirm && found != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.storage_old_files_confirm_title)) },
            text = { Text(stringResource(Res.string.storage_old_files_confirm_body, fileCount(found.files), megabytes(found.bytes))) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    controller.removeOldFiles()
                }) { Text(stringResource(Res.string.storage_old_files_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.storage_cancel)) } },
        )
    }
    if (pickDates) {
        OldFilesDates(range, pickableDays(archiveDays), onDismiss = { pickDates = false }, onPick = {
            pickDates = false
            controller.setOldFilesRange(it)
        })
    }
}

/**
 * The files removed from this phone that sync skips, shown only when there are some. Download again
 * asks once, then forgets them, so the next sync downloads them when a sensor still has them.
 */
@Composable
private fun RemovedFilesRow(controller: SettingsController, enabled: Boolean) {
    val removed by controller.removedFiles.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (removed <= 0) return
    val title = stringResource(Res.string.storage_removed_files)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = MinTouchTarget).cardSemantics(), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(fileCount(removed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The visible words first, then what they act on.
        val againLabel = stringResource(Res.string.storage_removed_files_again)
        val again = spokenList(listOf(againLabel, title), listSeparator())
        TextButton(onClick = { confirm = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = again }) {
            Text(againLabel)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.storage_removed_files_confirm_title)) },
            text = { Text(stringResource(Res.string.storage_removed_files_confirm_body, fileCount(removed))) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    controller.downloadRemovedAgain()
                }) { Text(stringResource(Res.string.storage_removed_files_again)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.storage_cancel)) } },
        )
    }
}

/** The status lines under Old format files. A partial removal says what stayed and why. */
@Composable
private fun oldFilesStatus(state: OldFilesState): List<String> = when (state) {
    OldFilesState.Idle -> emptyList()
    OldFilesState.Finding -> listOf(stringResource(Res.string.storage_old_files_finding))
    is OldFilesState.Found -> buildList {
        when {
            state.plan.files > 0 -> add(stringResource(Res.string.storage_old_files_summary, fileCount(state.plan.files), megabytes(state.plan.bytes)))
            // Unreadable files may be old too, so the card never says none were found.
            state.plan.skipped == 0 -> add(stringResource(Res.string.storage_old_files_none))
        }
        if (state.plan.skipped > 0) add(stringResource(Res.string.storage_old_files_unreadable, fileCount(state.plan.skipped)))
    }
    is OldFilesState.Removing -> listOf(stringResource(Res.string.storage_old_files_summary, fileCount(state.plan.files), megabytes(state.plan.bytes)))
    is OldFilesState.Removed -> removedStatus(state.result)
    OldFilesState.Failed -> listOf(stringResource(Res.string.storage_old_files_failed))
    OldFilesState.RemoveFailed -> listOf(stringResource(Res.string.storage_old_files_remove_failed))
}

@Composable
private fun removedStatus(result: OldFilesResult): List<String> = removedLines(result).map { line ->
    when (line) {
        RemovedLine.Removed -> stringResource(Res.string.storage_old_files_removed, fileCount(result.removed), megabytes(result.bytes))
        RemovedLine.NoneRemoved -> stringResource(Res.string.storage_old_files_removed_none)
        RemovedLine.NotRemoved -> stringResource(Res.string.storage_old_files_not_removed, fileCount(result.failed))
        RemovedLine.Kept -> stringResource(Res.string.storage_old_files_kept, fileCount(result.kept))
        RemovedLine.HistoryLater -> stringResource(Res.string.storage_old_files_history_later)
    }
}

/** The status lines after a removal, in order. */
internal enum class RemovedLine { Removed, NoneRemoved, NotRemoved, Kept, HistoryLater }

/** What went, what stayed and why. */
internal fun removedLines(result: OldFilesResult): List<RemovedLine> = buildList {
    add(if (result.removed > 0) RemovedLine.Removed else RemovedLine.NoneRemoved)
    if (result.failed > 0) add(RemovedLine.NotRemoved)
    if (result.kept > 0) add(RemovedLine.Kept)
    if (result.rebuildFailed) add(RemovedLine.HistoryLater)
}

@Composable
private fun fileCount(n: Int): String = pluralStringResource(Res.plurals.storage_old_files_count, n, n)

/** A UTC date range picker over [days], or every date when null. Apply with no dates chosen means every date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OldFilesDates(range: OldFilesRange?, days: LongRange?, onDismiss: () -> Unit, onPick: (OldFilesRange?) -> Unit) {
    val shown = range?.takeIf { days == null || (it.fromEpochDay in days && it.toEpochDay in days) }
    val selectable = days?.let {
        val years = yearOf(it.first)..yearOf(it.last)
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.floorDiv(DAY_MS) in it

            override fun isSelectableYear(year: Int) = year in years
        }
    }
    val state = if (days != null && selectable != null) {
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = shown?.let { it.fromEpochDay * DAY_MS },
            initialSelectedEndDateMillis = shown?.let { it.toEpochDay * DAY_MS },
            initialDisplayedMonthMillis = (shown?.fromEpochDay ?: days.last) * DAY_MS,
            yearRange = yearOf(days.first)..yearOf(days.last),
            selectableDates = selectable,
        )
    } else {
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = shown?.let { it.fromEpochDay * DAY_MS },
            initialSelectedEndDateMillis = shown?.let { it.toEpochDay * DAY_MS },
        )
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val from = state.selectedStartDateMillis?.floorDiv(DAY_MS)
                val to = state.selectedEndDateMillis?.floorDiv(DAY_MS) ?: from
                onPick(if (from != null && to != null && from <= to) OldFilesRange(from, to) else null)
            }) { Text(stringResource(Res.string.storage_apply)) }
        },
        dismissButton = {
            TextButton(onClick = { onPick(null) }) { Text(stringResource(Res.string.storage_old_files_all_dates)) }
        },
    ) {
        DateRangePicker(state, title = { Text(stringResource(Res.string.storage_old_files_pick_dates), Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

private const val DAY_MS = 86_400_000L

@Composable
private fun StorageRow(label: String, bytes: Long, enabled: Boolean = true, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // "Sensor files, 12.3 MB" as one focus stop of at least MinTouchTarget. The clear button stays its own.
        Column(Modifier.weight(1f).heightIn(min = MinTouchTarget).cardSemantics(), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(Res.string.storage_megabytes, megabytes(bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClear != null) {
            val description = stringResource(Res.string.storage_clear_named, label)
            TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(Res.string.storage_clear))
            }
        }
    }
}

@Composable
private fun About(appVersion: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val about = stringResource(Res.string.settings_about)
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            about.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            // Read as written, not as capitals a screen reader might spell out.
            modifier = Modifier.semantics {
                contentDescription = about
                heading()
            },
        )
        Text(stringResource(Res.string.settings_about_body, appVersion), style = MaterialTheme.typography.bodySmall, color = muted)
        Text(stringResource(Res.string.info_privacy), style = MaterialTheme.typography.bodySmall, color = muted)
        Text(stringResource(Res.string.settings_fonts), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun Caption(text: String, top: Dp = 8.dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top),
    )
}

/** One group of settings on a curved brand surface card. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private val SettingsCardShape = RoundedCornerShape(24.dp)

/**
 * The info affordance used when the host passes none (iOS). An outlined info icon that opens a
 * sheet with the title and short paragraphs.
 */
@Composable
fun SettingsInfoButton(title: String, body: List<String>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(InfoIcon, contentDescription = stringResource(Res.string.settings_more_about, title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                body.forEach {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Material "Info" outline (Apache 2.0), vendored so the module needs no icon library. */
private val InfoIcon: ImageVector = ImageVector.Builder(name = "Info", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
        pathData = addPathNodes(
            "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()

internal fun levelLabel(level: DetailLevel): StringResource = when (level) {
    DetailLevel.SIMPLE -> Res.string.level_simple
    DetailLevel.DETAILED -> Res.string.level_detailed
    DetailLevel.DEVELOPER -> Res.string.level_developer
}

internal fun levelCaption(level: DetailLevel): StringResource = when (level) {
    DetailLevel.SIMPLE -> Res.string.settings_level_simple
    DetailLevel.DETAILED -> Res.string.settings_level_detailed
    DetailLevel.DEVELOPER -> Res.string.settings_level_developer
}

internal fun unitLabel(system: UnitSystem): StringResource = when (system) {
    UnitSystem.AUTO -> Res.string.units_auto
    UnitSystem.METRIC -> Res.string.units_metric
    UnitSystem.IMPERIAL -> Res.string.units_imperial
}
