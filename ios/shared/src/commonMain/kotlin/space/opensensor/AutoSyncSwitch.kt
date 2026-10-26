package space.opensensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.AccessStatus
import space.opensensor.permissions.PermissionTriggers
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.UserAction
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.autosync_ios_hint
import space.opensensor.strings.resources.autosync_title

/**
 * The Auto-sync switch for one known sensor, the first card of its Device page and the only place
 * it lives on iOS. Notification permission is asked only from the tap that turns the switch on,
 * and only while iOS has never asked ([PermissionTriggers], AUTO_SYNC_ON makes it optional). The
 * switch turns on whatever the answer. A denial only hides the summary, so no settings dialog follows.
 */
@Composable
fun AutoSyncSwitch(settings: AutoSyncSettings, id: String, modifier: Modifier = Modifier) {
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
    // One handler for the row and the switch, so a tap anywhere asks for notifications exactly as the switch did.
    val onToggle: (Boolean) -> Unit = { checked ->
        if (!checked) {
            settings.set(id, false)
        } else {
            val turnOn = { settings.set(id, true) }
            if (AutoSyncAsk.shouldAsk(settings.notificationAccess())) access.run(onDeclined = turnOn, action = turnOn) else turnOn()
        }
    }
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The row is the switch, so VoiceOver and TalkBack read the label, the hint and on or off as one element.
        // The minimum size keeps the 48 dp height the switch gave the row when it owned the tap.
        Row(
            Modifier.minimumInteractiveComponentSize().toggleable(value = on, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.autosync_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.autosync_ios_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

/** When the switch tap shows the notification prompt. Pure, so the timing rule is tested. */
internal object AutoSyncAsk {
    /** Only a never-asked status prompts. A granted, denied or restricted one turns the switch on at once. */
    fun shouldAsk(status: AccessStatus): Boolean =
        status == AccessStatus.NOT_DETERMINED && AccessKind.NOTIFICATIONS in PermissionTriggers.table.getValue(UserAction.AUTO_SYNC_ON).optional
}
