package earth.walkthru.opensensor.devices

import androidx.compose.runtime.Composable
import earth.walkthru.opensensor.devices.resources.Res
import earth.walkthru.opensensor.devices.resources.perm_bt_body
import earth.walkthru.opensensor.devices.resources.perm_bt_settings_body
import earth.walkthru.opensensor.devices.resources.perm_bt_settings_title
import earth.walkthru.opensensor.devices.resources.perm_bt_title
import earth.walkthru.opensensor.permissions.AccessDialogTexts
import org.jetbrains.compose.resources.stringResource

/**
 * The Bluetooth rationale and settings dialogs in this module's words, for a platform route that
 * renders [earth.walkthru.opensensor.permissions.AccessDialogs] beside [MyDevicesScreen]. Android
 * keeps the same words in its own resources.
 */
@Composable
fun bluetoothAccessTexts(): AccessDialogTexts = AccessDialogTexts(
    rationaleTitle = stringResource(Res.string.perm_bt_title),
    rationaleBody = stringResource(Res.string.perm_bt_body),
    settingsTitle = stringResource(Res.string.perm_bt_settings_title),
    settingsBody = stringResource(Res.string.perm_bt_settings_body),
)
