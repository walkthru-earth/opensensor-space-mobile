package earth.walkthru.aqsync.ui.scan

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
import earth.walkthru.aqsync.ui.permissions.rememberLocalNetworkAccess
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.devices.DevicesAction
import earth.walkthru.opensensor.devices.DevicesPlatform
import earth.walkthru.opensensor.devices.MyDevicesScreen

/** Android collects in a background worker. Its Forget dialog keeps the earlier copy, without the iOS bond line. */
private val AndroidDevices = DevicesPlatform(collectInBackground = true, explainSystemBond = false)

/**
 * The Android route for the shared My devices screen. It binds Bluetooth and Local Network
 * permissions, notification permission for the Collect all worker and debug fake entries. The
 * list, scan lifecycle and Forget confirmation are shared in `:feature:devices`.
 */
@Composable
fun ScanScreen(
    onConnected: (address: String) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()
    val bluetooth = rememberBluetoothAccess()
    AccessDialogs(bluetooth)
    val localNetwork = rememberLocalNetworkAccess()
    AccessDialogs(localNetwork)
    // The Collect all worker shows a dataSync notification (API 33+). A denial only hides it.
    val notificationPermission = rememberNotificationPermission()

    MyDevicesScreen(
        state = state,
        level = level,
        bluetooth = bluetooth,
        platform = AndroidDevices,
        onScanTap = {
            viewModel.onAction(DevicesAction.StartScan)
            localNetwork.run(
                onDeclined = viewModel::onLocalNetworkDeclined,
                action = viewModel::startLanDiscoveryIfAllowed,
            )
        },
        onAction = { action ->
            when (action) {
                // Detail owns the link and still opens on the archive without Bluetooth.
                is DevicesAction.Open -> {
                    viewModel.onAction(action)
                    onConnected(action.id)
                }
                DevicesAction.CollectAll -> notificationPermission.ensureThen { viewModel.onAction(action) }
                else -> viewModel.onAction(action)
            }
        },
        topActions = {
            if (viewModel.showFakeEntry) {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) { Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.scan_more)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scan_fake)) },
                        leadingIcon = { Icon(AppIcons.BugReport, contentDescription = null) },
                        onClick = {
                            open = false
                            viewModel.connectFake()
                            onConnected(FakeTransport.FAKE_ADDRESS)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scan_fake_lan)) },
                        leadingIcon = { Icon(AppIcons.BugReport, contentDescription = null) },
                        onClick = {
                            open = false
                            viewModel.connectFakeLan()
                            onConnected(FakeTransport.FAKE_LAN_ADDRESS)
                        },
                    )
                }
            }
        },
    )
}
