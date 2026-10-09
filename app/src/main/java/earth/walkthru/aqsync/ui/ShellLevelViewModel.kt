package earth.walkthru.aqsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The stored detail level for the navigation host, so the shell opens Debug only at the Developer
 * level it reads itself, not on a screen's word. Null until the store is read, which counts as no.
 */
@HiltViewModel
class ShellLevelViewModel @Inject constructor(settings: AppSettings) : ViewModel() {
    val developer: StateFlow<Boolean?> = settings.advancedMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
