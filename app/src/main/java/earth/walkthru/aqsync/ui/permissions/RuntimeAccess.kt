package earth.walkthru.aqsync.ui.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.BluetoothStatus
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.permissions.AccessDialogTexts
import earth.walkthru.opensensor.permissions.AccessHooks
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.openAppSettings
import earth.walkthru.opensensor.permissions.AccessDialogs as SharedAccessDialogs

/**
 * The Android launchers behind the shared permission policy (`:ui:permissions`). The decisions
 * (ask, explain, settings) and the trigger table live there. This file only reads grants, launches
 * the system dialogs and opens the app's settings page.
 */
typealias RuntimeAccess = earth.walkthru.opensensor.permissions.RuntimeAccess

/** The runtime permissions behind every Bluetooth action (scan, connect, pair). Never location. */
val BluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

fun hasBluetoothPermissions(context: Context): Boolean =
    BluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Bluetooth scan and connect, plus the adapter switch. For Scan, Connect and Reconnect taps. */
@Composable
fun rememberBluetoothAccess(): RuntimeAccess {
    val context = LocalContext.current
    val access = remember { RuntimeAccess(AccessKind.BLUETOOTH) }
    val turnOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { access.onTurnOnResult() }
    BindRuntimeAccess(
        access = access,
        permissions = BluetoothPermissions,
        radioCheck = { BluetoothStatus.isEnabled(context) },
        requestTurnOn = { turnOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
    )
    // Follow the adapter while the screen is shown, so a scan starts when Bluetooth comes on.
    LaunchedEffect(access) {
        BluetoothStatus.enabledFlow(context).collect { access.radioOn = it }
    }
    return access
}

/** `ACCESS_LOCAL_NETWORK` (API 37+). Asked only from a Wi-Fi action. Always ready below API 37. */
@Composable
fun rememberLocalNetworkAccess(): RuntimeAccess {
    val access = remember { RuntimeAccess(AccessKind.LOCAL_NETWORK) }
    BindRuntimeAccess(access, permissions = listOfNotNull(LanPermission.name).toTypedArray(), radioCheck = { true })
    return access
}

@Composable
private fun BindRuntimeAccess(
    access: RuntimeAccess,
    permissions: Array<String>,
    radioCheck: () -> Boolean,
    requestTurnOn: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val check = { permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access.onPermissionResult() }
    access.bind(
        AccessHooks.rationaleBased(
            granted = check,
            rationaleDue = { activity != null && permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } },
            requestPermission = { if (permissions.isEmpty()) access.onPermissionResult() else launcher.launch(permissions) },
            openSettings = { openAppSettings(context) },
            radioOn = radioCheck,
            requestTurnOn = requestTurnOn,
        ),
    )
    // Returning from system settings may have changed the answer.
    LifecycleResumeEffect(access) {
        access.refresh()
        onPauseOrDispose { }
    }
}

/** The words and icon of each permission's in-context dialogs. */
@Composable
private fun accessTexts(kind: AccessKind): AccessDialogTexts = when (kind) {
    AccessKind.LOCAL_NETWORK -> AccessDialogTexts(
        rationaleTitle = stringResource(R.string.perm_lan_title),
        rationaleBody = stringResource(R.string.perm_lan_body),
        settingsTitle = stringResource(R.string.perm_lan_settings_title),
        settingsBody = stringResource(R.string.perm_lan_settings_body),
        continueLabel = stringResource(R.string.perm_continue),
        notNow = stringResource(R.string.action_not_now),
        openSettings = stringResource(R.string.perm_open_settings),
        cancel = stringResource(R.string.action_cancel),
        icon = AppIcons.Wifi,
    )
    else -> AccessDialogTexts(
        rationaleTitle = stringResource(R.string.perm_bt_title),
        rationaleBody = stringResource(R.string.perm_bt_body),
        settingsTitle = stringResource(R.string.perm_bt_settings_title),
        settingsBody = stringResource(R.string.perm_bt_settings_body),
        continueLabel = stringResource(R.string.perm_continue),
        notNow = stringResource(R.string.action_not_now),
        openSettings = stringResource(R.string.perm_open_settings),
        cancel = stringResource(R.string.action_cancel),
        icon = AppIcons.Bluetooth,
    )
}

/** The in-context rationale and the settings hint for [access]. Shown only when its flow asks. */
@Composable
fun AccessDialogs(access: RuntimeAccess) {
    SharedAccessDialogs(access, accessTexts(access.kind))
}
