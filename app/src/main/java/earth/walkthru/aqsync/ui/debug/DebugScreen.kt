package earth.walkthru.aqsync.ui.debug

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.showDeviceEvent
import earth.walkthru.opensensor.debug.DebugScreen as SharedDebugScreen

/**
 * Developer level only. The screen itself is shared with iOS (`:feature:debug`). Android adds its
 * connection banner, the device event snackbar, and leaves when the session moves to another sensor.
 */
@Composable
fun DebugScreen(
    address: String,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel<DebugViewModel, DebugViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val developer by viewModel.developer.collectAsStateWithLifecycle()
    val sessionAddress by viewModel.sessionAddress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    CollectEvents(viewModel.events) { ev ->
        snackbar.showDeviceEvent(res, ev)
    }
    LaunchedEffect(sessionAddress) {
        if (sessionAddress != null && sessionAddress != address) onDisconnected()
    }
    SharedDebugScreen(
        controller = viewModel.controller,
        address = address,
        developer = developer,
        onBack = onBack,
        snackbar = snackbar,
        connectionBanner = { ConnectionBanner(it) },
    )
}
