package earth.walkthru.aqsync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.ui.components.Eyebrow

/**
 * The app's only settings: the **Advanced mode** switch (persisted in DataStore). Everything else
 * the user can change lives on the device and is edited from the Device screen.
 */
@Composable
fun SettingsSheet(onDismiss: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val advanced by viewModel.advancedMode.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Adds a Debug screen with the raw status JSON, the last frames on the link, the device's serial log and a raw command box. Off by default; nothing else changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(8.dp))
                Switch(checked = advanced, onCheckedChange = { viewModel.setAdvancedMode(it) })
            }
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
