package earth.walkthru.aqsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val detailLevel: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.SIMPLE)
    val clockSyncMode: StateFlow<ClockSyncMode> = settings.clockSyncMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockSyncMode.ASK)

    fun setDetailLevel(level: DetailLevel) {
        viewModelScope.launch { settings.setDetailLevel(level) }
    }

    fun setClockSyncMode(mode: ClockSyncMode) {
        viewModelScope.launch { settings.setClockSyncMode(mode) }
    }
}
