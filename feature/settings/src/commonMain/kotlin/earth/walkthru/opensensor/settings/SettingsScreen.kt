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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.settings.resources.Res
import earth.walkthru.opensensor.settings.resources.clock_mode_ask
import earth.walkthru.opensensor.settings.resources.clock_mode_ask_title
import earth.walkthru.opensensor.settings.resources.clock_mode_automatic
import earth.walkthru.opensensor.settings.resources.clock_mode_automatic_title
import earth.walkthru.opensensor.settings.resources.clock_mode_never
import earth.walkthru.opensensor.settings.resources.clock_mode_never_title
import earth.walkthru.opensensor.settings.resources.info_privacy
import earth.walkthru.opensensor.settings.resources.info_settings_body1
import earth.walkthru.opensensor.settings.resources.info_settings_body2
import earth.walkthru.opensensor.settings.resources.level_detailed
import earth.walkthru.opensensor.settings.resources.level_developer
import earth.walkthru.opensensor.settings.resources.level_simple
import earth.walkthru.opensensor.settings.resources.settings_about
import earth.walkthru.opensensor.settings.resources.settings_about_body
import earth.walkthru.opensensor.settings.resources.settings_clock
import earth.walkthru.opensensor.settings.resources.settings_detail_level
import earth.walkthru.opensensor.settings.resources.settings_fonts
import earth.walkthru.opensensor.settings.resources.settings_level_detailed
import earth.walkthru.opensensor.settings.resources.settings_level_developer
import earth.walkthru.opensensor.settings.resources.settings_level_simple
import earth.walkthru.opensensor.settings.resources.settings_more_about
import earth.walkthru.opensensor.settings.resources.settings_public_sensors
import earth.walkthru.opensensor.settings.resources.settings_public_sensors_body
import earth.walkthru.opensensor.settings.resources.settings_title
import earth.walkthru.opensensor.settings.resources.settings_units
import earth.walkthru.opensensor.settings.resources.settings_units_auto
import earth.walkthru.opensensor.settings.resources.units_auto
import earth.walkthru.opensensor.settings.resources.units_imperial
import earth.walkthru.opensensor.settings.resources.units_metric
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The Settings title, also the tab label on iOS. */
@Composable
fun settingsTitle(): String = stringResource(Res.string.settings_title)

/**
 * The Settings screen for both apps. The detail level, the Smart Sync clock consent, the units, the
 * one switch for Discover network use (AGENTS.md boundary) and About. Everything else the user can
 * change lives on the device and is edited from the device's own screens.
 *
 * [appVersion] is the host's version name. [automaticUnits] is what Automatic resolves to on this
 * phone now (METRIC or IMPERIAL). [showClockSync] hides the clock consent where the host has no Smart
 * Sync yet (iOS today). [infoButton] lets the host reuse its own info affordance.
 */
@Composable
fun SettingsScreen(
    controller: SettingsController,
    appVersion: String,
    automaticUnits: UnitSystem,
    modifier: Modifier = Modifier,
    showClockSync: Boolean = true,
    infoButton: @Composable (title: String, body: List<String>, modifier: Modifier) -> Unit = { t, b, m -> SettingsInfoButton(t, b, m) },
) {
    val stored by controller.prefs.collectAsState()
    val colors = MaterialTheme.colorScheme
    val title = settingsTitle()
    val infoBody = listOfNotNull(
        stringResource(Res.string.info_settings_body1),
        if (showClockSync) stringResource(Res.string.info_settings_body2) else null,
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
            if (showClockSync) {
                SettingsCard {
                    Choice(
                        label = stringResource(Res.string.settings_clock),
                        options = ClockSyncMode.entries,
                        selected = prefs.clockSyncMode,
                        optionLabel = { stringResource(clockLabel(it)) },
                        onSelect = controller::setClockSyncMode,
                    )
                    Caption(stringResource(clockCaption(prefs.clockSyncMode)))
                }
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

/** The whole row toggles, with a touch target of at least [FloatingControl.Size]. */
@Composable
private fun PublicSensorsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FloatingControl.Size)
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

internal fun clockLabel(mode: ClockSyncMode): StringResource = when (mode) {
    ClockSyncMode.ASK -> Res.string.clock_mode_ask_title
    ClockSyncMode.AUTOMATIC -> Res.string.clock_mode_automatic_title
    ClockSyncMode.NEVER -> Res.string.clock_mode_never_title
}

internal fun clockCaption(mode: ClockSyncMode): StringResource = when (mode) {
    ClockSyncMode.ASK -> Res.string.clock_mode_ask
    ClockSyncMode.AUTOMATIC -> Res.string.clock_mode_automatic
    ClockSyncMode.NEVER -> Res.string.clock_mode_never
}

internal fun unitLabel(system: UnitSystem): StringResource = when (system) {
    UnitSystem.AUTO -> Res.string.units_auto
    UnitSystem.METRIC -> Res.string.units_metric
    UnitSystem.IMPERIAL -> Res.string.units_imperial
}
