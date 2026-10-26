package space.opensensor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.HistoryRepository
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.local.database.StationEpochDao
import space.opensensor.data.settings.AppSettings
import space.opensensor.history.HistoryController
import space.opensensor.settings.DetailLevel

/**
 * The Android host of the shared [HistoryController]: Room's index and tiers through
 * [HistoryRepository], the station from the known device and its generations from the epoch table.
 * History works from the archive alone, no session needed, and shows the link chip when one exists.
 */
@HiltViewModel(assistedFactory = HistoryViewModel.Factory::class)
class HistoryViewModel @AssistedInject constructor(
    manager: DeviceConnectionManager,
    @Assisted deviceAddress: String,
    history: HistoryRepository,
    knownDeviceDao: KnownDeviceDao,
    epochDao: StationEpochDao,
    settings: AppSettings,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): HistoryViewModel
    }

    val controller = HistoryController(
        scope = viewModelScope,
        reader = history.reader,
        tiers = history.tiers,
        index = history.index,
        currentStation = knownDeviceDao.observeAll().map { list -> list.firstOrNull { it.address == deviceAddress }?.station },
        stations = epochDao.observeForDevice(deviceAddress).map { epochs -> epochs.map { it.station } },
    )

    val transport: StateFlow<TransportKind?> = manager.controller(deviceAddress).activeTransport

    val level: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.SIMPLE)
}
