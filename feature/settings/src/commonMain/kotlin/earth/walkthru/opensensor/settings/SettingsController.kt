package earth.walkthru.opensensor.settings

import earth.walkthru.aqsync.settings.AppPrefs
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions behind [SettingsScreen], free of Android and Hilt (the DiscoverController
 * pattern). Android wraps it in a Hilt ViewModel with `viewModelScope`, iOS builds it in `AppRoot`.
 *
 * [prefs] is null until the store has been read once. Hosts treat null as "not known yet", so the
 * Discover network switch never reads as on before the saved choice arrives.
 */
class SettingsController(private val scope: CoroutineScope, private val repository: SettingsRepository) {
    val prefs: StateFlow<AppPrefs?> = repository.prefs.stateIn(scope, SharingStarted.Eagerly, null)

    fun setDetailLevel(level: DetailLevel) {
        scope.launch { repository.setDetailLevel(level) }
    }

    fun setClockSyncMode(mode: ClockSyncMode) {
        scope.launch { repository.setClockSyncMode(mode) }
    }

    fun setShowPublicSensors(on: Boolean) {
        scope.launch { repository.setShowPublicSensors(on) }
    }

    fun setUnitSystem(system: UnitSystem) {
        scope.launch { repository.setUnitSystem(system) }
    }
}
