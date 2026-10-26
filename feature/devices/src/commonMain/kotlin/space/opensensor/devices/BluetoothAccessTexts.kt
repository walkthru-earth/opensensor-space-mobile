package space.opensensor.devices

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import space.opensensor.devices.resources.Res
import space.opensensor.devices.resources.perm_bt_body
import space.opensensor.devices.resources.perm_bt_settings_body
import space.opensensor.devices.resources.perm_bt_settings_title
import space.opensensor.devices.resources.perm_bt_title
import space.opensensor.permissions.AccessDialogTexts

/**
 * The Bluetooth rationale and settings dialogs in this module's words, for a platform route that
 * renders [space.opensensor.permissions.AccessDialogs] beside [MyDevicesScreen]. Android
 * keeps the same words in its own resources.
 */
@Composable
fun bluetoothAccessTexts(): AccessDialogTexts = AccessDialogTexts(
    rationaleTitle = stringResource(Res.string.perm_bt_title),
    rationaleBody = stringResource(Res.string.perm_bt_body),
    settingsTitle = stringResource(Res.string.perm_bt_settings_title),
    settingsBody = stringResource(Res.string.perm_bt_settings_body),
)
