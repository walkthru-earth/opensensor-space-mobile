package space.opensensor.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.opensensor.history.historyTitle
import space.opensensor.today.DeviceSectionTabs
import space.opensensor.today.DeviceTab
import space.opensensor.today.TransportChip
import space.opensensor.ui.components.LocalUnits
import space.opensensor.history.HistoryScreen as SharedHistoryScreen

/**
 * Day, Week, Month and Year of one logger from the phone archive, the shared History screen under the
 * Android device header (link chip and section tabs). Works offline and without a session.
 */
@Composable
fun HistoryScreen(
    address: String,
    onOpenToday: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDevice: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel<HistoryViewModel, HistoryViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val transport by viewModel.transport.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    SharedHistoryScreen(
        controller = viewModel.controller,
        units = LocalUnits.current,
        level = level,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(historyTitle()) },
                    actions = { if (level.detailed) TransportChip(transport, Modifier.padding(end = 12.dp)) },
                )
                DeviceSectionTabs(DeviceTab.HISTORY, containerColor = MaterialTheme.colorScheme.surface) {
                    when (it) {
                        DeviceTab.TODAY -> onOpenToday()
                        DeviceTab.FILES -> onOpenFiles()
                        DeviceTab.DEVICE -> onOpenDevice()
                        DeviceTab.HISTORY -> Unit
                    }
                }
            }
        },
    )
}
