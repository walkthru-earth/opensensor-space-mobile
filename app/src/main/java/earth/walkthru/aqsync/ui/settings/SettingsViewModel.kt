package earth.walkthru.aqsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val advancedMode: StateFlow<Boolean> = settings.advancedMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAdvancedMode(on: Boolean) {
        viewModelScope.launch { settings.setAdvancedMode(on) }
    }
}
