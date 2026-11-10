package space.opensensor.ui.history

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.opensensor.history.HistoryTopBar
import space.opensensor.today.TransportChip
import space.opensensor.ui.components.LocalUnits
import space.opensensor.history.HistoryScreen as SharedHistoryScreen

/**
 * Day, Week, Month and Year of one logger from the phone archive, the shared History screen and its
 * shared header with the link chip. Back returns to the Station page. Works offline and without a session.
 */
@Composable
fun HistoryScreen(
    address: String,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel<HistoryViewModel, HistoryViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val transport by viewModel.transport.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    SharedHistoryScreen(
        controller = viewModel.controller,
        units = LocalUnits.current,
        level = level,
        topBar = {
            HistoryTopBar(onBack = onBack, actions = { if (level.detailed) TransportChip(transport, Modifier.padding(end = 12.dp)) })
        },
    )
}
