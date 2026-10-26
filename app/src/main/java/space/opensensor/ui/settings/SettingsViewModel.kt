package space.opensensor.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import space.opensensor.data.storage.AppStorageControls
import space.opensensor.settings.SettingsController
import space.opensensor.settings.SettingsRepository
import javax.inject.Inject

/** Holds the shared [SettingsController] (`:feature:settings`) for the Settings tab, so it survives rotation. */
@HiltViewModel
class SettingsViewModel @Inject constructor(repository: SettingsRepository, storage: AppStorageControls) : ViewModel() {
    val controller = SettingsController(viewModelScope, repository, storage, log = { Log.w(TAG, it) })

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
