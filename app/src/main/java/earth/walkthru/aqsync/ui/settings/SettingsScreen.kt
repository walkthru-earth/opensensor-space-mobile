package earth.walkthru.aqsync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.resolveUnits
import earth.walkthru.aqsync.units.UnitSystem

/**
 * The Settings tab: the **detail level** (Simple, Detailed, Developer, persisted in DataStore),
 * the Smart Sync clock consent and "Show public sensors" for the Discover map. Everything else the
 * user can change lives on the device and is edited from the Device section. Same three levels,
 * same names, on iOS.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()
    val clockMode by viewModel.clockSyncMode.collectAsStateWithLifecycle()
    val showPublic by viewModel.showPublicSensors.collectAsStateWithLifecycle()
    val units by viewModel.unitSystem.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val title = stringResource(R.string.settings_title)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(title) },
                actions = { InfoButton(InfoContent(title, stringResource(R.string.info_settings_body1), stringResource(R.string.info_settings_body2))) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            Text(stringResource(R.string.settings_detail_level), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DetailLevel.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = level == l,
                        onClick = { viewModel.setDetailLevel(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, DetailLevel.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    when (l) {
                                        DetailLevel.SIMPLE -> R.string.level_simple
                                        DetailLevel.DETAILED -> R.string.level_detailed
                                        DetailLevel.DEVELOPER -> R.string.level_developer
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    when (level) {
                        DetailLevel.SIMPLE -> R.string.settings_level_simple
                        DetailLevel.DETAILED -> R.string.settings_level_detailed
                        DetailLevel.DEVELOPER -> R.string.settings_level_developer
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_clock), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ClockSyncMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = clockMode == mode,
                        onClick = { viewModel.setClockSyncMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ClockSyncMode.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        ClockSyncMode.ASK -> R.string.clock_mode_ask_title
                                        ClockSyncMode.AUTOMATIC -> R.string.clock_mode_automatic_title
                                        ClockSyncMode.NEVER -> R.string.clock_mode_never_title
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    when (clockMode) {
                        ClockSyncMode.ASK -> R.string.clock_mode_ask
                        ClockSyncMode.AUTOMATIC -> R.string.clock_mode_automatic
                        ClockSyncMode.NEVER -> R.string.clock_mode_never
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(Modifier.height(24.dp))
            UnitsRow(units, viewModel::setUnitSystem)
            Spacer(Modifier.height(24.dp))
            // The one switch for Discover network use (feed and basemap), AGENTS.md boundary.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_public_sensors), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = showPublic, onCheckedChange = viewModel::setShowPublicSensors)
            }
            Text(stringResource(R.string.settings_public_sensors_body), style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.height(24.dp))
            Eyebrow(stringResource(R.string.settings_about))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(stringResource(R.string.info_privacy), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.settings_fonts), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Display units. Automatic, Metric or Imperial. Changes labels only, never stored data. */
@Composable
private fun UnitsRow(choice: UnitSystem, onChoice: (UnitSystem) -> Unit) {
    fun label(system: UnitSystem) = when (system) {
        UnitSystem.AUTO -> R.string.units_auto
        UnitSystem.METRIC -> R.string.units_metric
        UnitSystem.IMPERIAL -> R.string.units_imperial
    }
    Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        UnitSystem.entries.forEachIndexed { i, system ->
            SegmentedButton(
                selected = choice == system,
                onClick = { onChoice(system) },
                shape = SegmentedButtonDefaults.itemShape(i, UnitSystem.entries.size),
                label = { Text(stringResource(label(system))) },
            )
        }
    }
    if (choice == UnitSystem.AUTO) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_units_auto, stringResource(label(resolveUnits(UnitSystem.AUTO)))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
