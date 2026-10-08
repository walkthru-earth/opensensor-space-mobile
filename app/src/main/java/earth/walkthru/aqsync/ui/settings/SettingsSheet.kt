package earth.walkthru.aqsync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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

/**
 * The app's settings: the **detail level** (Simple, Detailed, Developer, persisted in DataStore)
 * and the Smart Sync clock consent. Everything else the user can change lives on the device and
 * is edited from the Device screen. Same three levels, same names, on iOS.
 */
@Composable
fun SettingsSheet(onDismiss: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()
    val clockMode by viewModel.clockSyncMode.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            val title = stringResource(R.string.settings_title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                InfoButton(InfoContent(title, stringResource(R.string.info_settings_body1), stringResource(R.string.info_settings_body2)))
            }
            Spacer(Modifier.height(16.dp))
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
            Eyebrow(stringResource(R.string.settings_about))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(stringResource(R.string.info_privacy), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.settings_fonts), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
