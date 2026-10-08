package earth.walkthru.aqsync.ui.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.BluetoothStatus

/** The runtime permissions behind every Bluetooth action (scan, connect, pair). Never location. */
val BluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

fun hasBluetoothPermissions(context: Context): Boolean =
    BluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The words of one permission's in-context dialogs. */
data class AccessTexts(
    @param:StringRes val rationaleTitle: Int,
    @param:StringRes val rationaleBody: Int,
    @param:StringRes val settingsTitle: Int,
    @param:StringRes val settingsBody: Int,
    val icon: ImageVector,
)

internal enum class AccessDialog { RATIONALE, SETTINGS }

/**
 * A permission (and optionally a radio) that an action needs. Call [run] from a tap. Nothing is
 * requested at launch or from composition. Render [AccessDialogs] next to the screen that owns it.
 */
@Stable
class RuntimeAccess internal constructor(val texts: AccessTexts) {
    /** Permission held (or nothing to hold on this API level). Updated on resume and after a dialog. */
    var granted by mutableStateOf(false)
        internal set

    /** The radio is on, or the action needs no radio. */
    var radioOn by mutableStateOf(true)
        internal set

    /** True when an action can run without any dialog. Safe to read in composition. */
    val ready: Boolean get() = granted && radioOn

    internal var dialog by mutableStateOf<AccessDialog?>(null)

    internal var check: () -> Boolean = { true }
    internal var rationaleDue: () -> Boolean = { false }
    internal var radioCheck: () -> Boolean = { true }
    internal var launchPermission: () -> Unit = {}
    internal var launchTurnOn: () -> Unit = {}
    internal var launchSettings: () -> Unit = {}

    private var onReady: (() -> Unit)? = null
    private var onDeclined: (() -> Unit)? = null

    /** Run [action] once the permission (and radio) are in place, asking only if needed. */
    fun run(onDeclined: () -> Unit = {}, action: () -> Unit) {
        this.onReady = action
        this.onDeclined = onDeclined
        handle(PermissionFlow.onTap(check(), rationaleDue(), radioCheck()))
    }

    internal fun onPermissionResult() {
        granted = check()
        handle(PermissionFlow.onResult(granted, rationaleDue(), radioCheck()))
    }

    internal fun onTurnOnResult() {
        radioOn = radioCheck()
        handle(if (radioOn) AccessStep.READY else AccessStep.DECLINED)
    }

    internal fun confirmRationale() {
        dialog = null
        launchPermission()
    }

    internal fun openSettings() {
        dialog = null
        launchSettings()
        handle(AccessStep.DECLINED)
    }

    internal fun dismiss() {
        dialog = null
        handle(AccessStep.DECLINED)
    }

    private fun handle(step: AccessStep) {
        when (step) {
            AccessStep.READY -> finish(onReady)
            AccessStep.TURN_ON -> launchTurnOn()
            AccessStep.ASK -> launchPermission()
            AccessStep.EXPLAIN_THEN_ASK -> dialog = AccessDialog.RATIONALE
            AccessStep.OPEN_SETTINGS -> dialog = AccessDialog.SETTINGS
            AccessStep.DECLINED -> finish(onDeclined)
        }
    }

    private fun finish(callback: (() -> Unit)?) {
        onReady = null
        onDeclined = null
        callback?.invoke()
    }
}

/** Bluetooth scan and connect, plus the adapter switch. For Scan, Connect and Reconnect taps. */
@Composable
fun rememberBluetoothAccess(): RuntimeAccess {
    val context = LocalContext.current
    val access = rememberRuntimeAccess(
        permissions = BluetoothPermissions,
        radioCheck = { BluetoothStatus.isEnabled(context) },
        texts = AccessTexts(
            rationaleTitle = R.string.perm_bt_title,
            rationaleBody = R.string.perm_bt_body,
            settingsTitle = R.string.perm_bt_settings_title,
            settingsBody = R.string.perm_bt_settings_body,
            icon = Icons.Rounded.Bluetooth,
        ),
    )
    val turnOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { access.onTurnOnResult() }
    access.launchTurnOn = { turnOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    // Follow the adapter while the screen is shown, so a scan starts when Bluetooth comes on.
    LaunchedEffect(access) {
        BluetoothStatus.enabledFlow(context).collect { access.radioOn = it }
    }
    return access
}

/** `ACCESS_LOCAL_NETWORK` (API 37+). Asked only from a Wi-Fi action. Always ready below API 37. */
@Composable
fun rememberLocalNetworkAccess(): RuntimeAccess = rememberRuntimeAccess(
    permissions = listOfNotNull(LanPermission.name).toTypedArray(),
    radioCheck = { true },
    texts = AccessTexts(
        rationaleTitle = R.string.perm_lan_title,
        rationaleBody = R.string.perm_lan_body,
        settingsTitle = R.string.perm_lan_settings_title,
        settingsBody = R.string.perm_lan_settings_body,
        icon = Icons.Rounded.Wifi,
    ),
)

@Composable
private fun rememberRuntimeAccess(permissions: Array<String>, radioCheck: () -> Boolean, texts: AccessTexts): RuntimeAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val check = { permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    val access = remember(texts) { RuntimeAccess(texts).also { it.granted = check(); it.radioOn = radioCheck() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access.onPermissionResult() }
    access.check = check
    access.radioCheck = radioCheck
    access.rationaleDue = { activity != null && permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } }
    access.launchPermission = { if (permissions.isEmpty()) access.onPermissionResult() else launcher.launch(permissions) }
    access.launchSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    // Returning from system settings may have changed the answer.
    LifecycleResumeEffect(access) {
        access.granted = check()
        access.radioOn = radioCheck()
        onPauseOrDispose { }
    }
    return access
}

/** The in-context rationale and the settings hint for [access]. Shown only when its flow asks. */
@Composable
fun AccessDialogs(access: RuntimeAccess) {
    val texts = access.texts
    when (access.dialog) {
        AccessDialog.RATIONALE -> AlertDialog(
            onDismissRequest = access::dismiss,
            icon = { Icon(texts.icon, contentDescription = null) },
            title = { Text(stringResource(texts.rationaleTitle)) },
            text = { Text(stringResource(texts.rationaleBody)) },
            confirmButton = { TextButton(onClick = access::confirmRationale) { Text(stringResource(R.string.perm_continue)) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(stringResource(R.string.action_not_now)) } },
        )
        AccessDialog.SETTINGS -> AlertDialog(
            onDismissRequest = access::dismiss,
            icon = { Icon(texts.icon, contentDescription = null) },
            title = { Text(stringResource(texts.settingsTitle)) },
            text = { Text(stringResource(texts.settingsBody)) },
            confirmButton = { TextButton(onClick = access::openSettings) { Text(stringResource(R.string.perm_open_settings)) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
}
