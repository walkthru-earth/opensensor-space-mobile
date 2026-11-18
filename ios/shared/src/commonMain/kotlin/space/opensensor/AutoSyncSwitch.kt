package space.opensensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import space.opensensor.device.AutoSyncCadence
import space.opensensor.device.AutoSyncCard
import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.AccessStatus
import space.opensensor.permissions.PermissionTriggers
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.permissions.UserAction

/**
 * The Auto-sync card for one known sensor, the first card of its Device page and the only place it
 * lives on iOS. The card is the one Android shows (#303), and iOS picks when it runs. Notification
 * permission is asked only from the tap that turns the switch on, and only while iOS has never
 * asked ([PermissionTriggers], AUTO_SYNC_ON makes it optional). The switch turns on whatever the
 * answer. A denial only hides the summary, so no settings dialog follows.
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
    // One handler for the whole switch row, so a tap anywhere on it asks for notifications.
    val onToggle: (Boolean) -> Unit = { checked ->
        if (!checked) {
            settings.set(id, false)
        } else {
            val turnOn = { settings.set(id, true) }
            if (AutoSyncAsk.shouldAsk(settings.notificationAccess())) access.run(onDeclined = turnOn, action = turnOn) else turnOn()
        }
    }
    AutoSyncCard(on = on, cadence = AutoSyncCadence.SystemPicks, onToggle = onToggle, modifier = modifier)
}

/** When the switch tap shows the notification prompt. Pure, so the timing rule is tested. */
internal object AutoSyncAsk {
    /** Only a never-asked status prompts. A granted, denied or restricted one turns the switch on at once. */
    fun shouldAsk(status: AccessStatus): Boolean =
        status == AccessStatus.NOT_DETERMINED && AccessKind.NOTIFICATIONS in PermissionTriggers.table.getValue(UserAction.AUTO_SYNC_ON).optional
}
