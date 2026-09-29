package earth.walkthru.aqsync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.ui.components.Eyebrow

/**
 * The app's only setting: the **detail level** (Simple · Detailed · Developer, persisted in
 * DataStore). Everything else the user can change lives on the device and is edited from the Device
 * screen. Same three levels, same names, on iOS.
 */
@Composable
fun SettingsSheet(onDismiss: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()
    val clockMode by viewModel.clockSyncMode.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Detail level", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DetailLevel.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = level == l,
                        onClick = { viewModel.setDetailLevel(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, DetailLevel.entries.size),
                        label = { Text(l.title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(level.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Cosmetic: every level reads the same data from the same logger. Nothing is hidden from the archive or the export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text("Smart Sync clock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ClockSyncMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = clockMode == mode,
                        onClick = { viewModel.setClockSyncMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ClockSyncMode.entries.size),
                        label = { Text(mode.title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(clockMode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Smart Sync never rewrites raw files. Finalizing buffered rows remains a separate confirmed action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
            Eyebrow("about")
            Spacer(Modifier.height(4.dp))
            Text(
                "AQ Sync ${BuildConfig.VERSION_NAME} · protocol v2 · offline-first. Network access is used only for the LAN link to a logger found on your own network; there is no cloud endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
