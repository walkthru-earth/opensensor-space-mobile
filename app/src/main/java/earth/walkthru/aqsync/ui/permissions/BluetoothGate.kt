package earth.walkthru.aqsync.ui.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.BluetoothStatus

/** The two — and only two — runtime permissions this app holds. */
val BluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/**
 * Shows the rationale + request flow until both Bluetooth permissions are granted, then a
 * "Bluetooth is off" prompt (ACTION_REQUEST_ENABLE) until the adapter is on. Only then is
 * [content] composed, so nothing below this gate needs to re-check permissions.
 */
@Composable
fun BluetoothGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(hasAllPermissions(context)) }
    var deniedOnce by remember { mutableStateOf(false) }

    // Re-check when the user returns from system settings.
    LifecycleResumeEffect(Unit) {
        granted = hasAllPermissions(context)
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (!granted) deniedOnce = true
    }

    if (!granted) {
        GateScreen(
            icon = Icons.Rounded.Bluetooth,
            title = stringResource(R.string.perm_title),
            body = if (deniedOnce) stringResource(R.string.perm_denied) else stringResource(R.string.perm_body),
            primaryLabel = stringResource(R.string.perm_grant),
            onPrimary = { permissionLauncher.launch(BluetoothPermissions) },
            secondaryLabel = if (deniedOnce) stringResource(R.string.perm_open_settings) else null,
            onSecondary = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
        )
        return
    }

    val enabled by remember { BluetoothStatus.enabledFlow(context) }
        .collectAsStateWithLifecycle(initialValue = BluetoothStatus.isEnabled(context))
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    if (!enabled) {
        GateScreen(
            icon = Icons.Rounded.BluetoothDisabled,
            title = stringResource(R.string.bt_off_title),
            body = stringResource(R.string.bt_off_body),
            primaryLabel = stringResource(R.string.bt_turn_on),
            onPrimary = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        )
        return
    }

    content()
}

fun hasAllPermissions(context: android.content.Context): Boolean =
    BluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun GateScreen(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}
