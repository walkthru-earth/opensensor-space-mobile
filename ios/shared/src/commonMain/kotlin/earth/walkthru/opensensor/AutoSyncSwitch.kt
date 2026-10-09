package earth.walkthru.opensensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.AccessStatus
import earth.walkthru.opensensor.permissions.PermissionTriggers
import earth.walkthru.opensensor.permissions.RuntimeAccess
import earth.walkthru.opensensor.permissions.UserAction
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.autosync_ios_hint
import earth.walkthru.opensensor.strings.resources.autosync_ios_keep_open
import earth.walkthru.opensensor.strings.resources.autosync_title
import earth.walkthru.opensensor.strings.resources.files_bg_event
import earth.walkthru.opensensor.strings.resources.files_bg_sync
import org.jetbrains.compose.resources.stringResource

/**
 * The Auto-sync switch and "Sync now in background" for one known sensor, a small block the device
 * page places where it likes. Notification permission is asked only from the tap that turns the
 * switch on, and only while iOS has never asked ([PermissionTriggers], AUTO_SYNC_ON makes it
 * optional). The switch turns on whatever the answer. A denial only hides the summary, so no
 * settings dialog follows. [syncInBackground] hands the sensor to the background run and answers
 * true only when iOS took it as a continued task, which keeps going after the user leaves the app.
 */
@Composable
fun AutoSyncSwitch(settings: AutoSyncSettings, id: String, syncInBackground: (String) -> Boolean, modifier: Modifier = Modifier) {
    val entries by settings.state.collectAsState()
    val on = entries.any { it.peripheral.equals(id, ignoreCase = true) }
    val access = remember { RuntimeAccess(AccessKind.NOTIFICATIONS) }
    access.bind(
        AccessHooks.statusBased(
            status = settings::notificationAccess,
            requestPermission = { settings.askNotifications { access.onPermissionResult() } },
            openSettings = {},
        ),
    )
    // Null until the button is tapped, then whether iOS took it as a continued task.
    var continued by remember(id) { mutableStateOf<Boolean?>(null) }
    val title = stringResource(Res.string.autosync_title)
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.autosync_ios_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = on,
                onCheckedChange = { checked ->
                    if (!checked) {
                        settings.set(id, false)
                    } else {
                        val turnOn = { settings.set(id, true) }
                        if (AutoSyncAsk.shouldAsk(settings.notificationAccess())) access.run(onDeclined = turnOn, action = turnOn) else turnOn()
                    }
                },
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        OutlinedButton(onClick = { continued = syncInBackground(id) }) { Text(stringResource(Res.string.files_bg_sync)) }
        continued?.let { taken ->
            val hint = if (taken) Res.string.files_bg_event else Res.string.autosync_ios_keep_open
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** When the switch tap shows the notification prompt. Pure, so the timing rule is tested. */
internal object AutoSyncAsk {
    /** Only a never-asked status prompts. A granted, denied or restricted one turns the switch on at once. */
    fun shouldAsk(status: AccessStatus): Boolean =
        status == AccessStatus.NOT_DETERMINED && AccessKind.NOTIFICATIONS in PermissionTriggers.table.getValue(UserAction.AUTO_SYNC_ON).optional
}
