package earth.walkthru.aqsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.storage.AppStorageControls
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.opensensor.settings.SettingsController
import javax.inject.Inject

/** Holds the shared [SettingsController] (`:feature:settings`) for the Settings tab, so it survives rotation. */
@HiltViewModel
class SettingsViewModel @Inject constructor(repository: SettingsRepository, storage: AppStorageControls) : ViewModel() {
    val controller = SettingsController(viewModelScope, repository, storage)
}
