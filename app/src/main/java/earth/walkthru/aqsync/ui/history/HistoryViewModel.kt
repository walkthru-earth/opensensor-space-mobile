package earth.walkthru.aqsync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.HistoryRepository
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.opensensor.history.HistoryController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
