package earth.walkthru.opensensor.settings

import earth.walkthru.aqsync.settings.AppPrefs
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions behind [SettingsScreen], free of Android and Hilt (the DiscoverController
 * pattern). Android wraps it in a Hilt ViewModel with `viewModelScope`, iOS builds it in `AppRoot`.
 *
 * [prefs] is null until the store has been read once. Hosts treat null as "not known yet", so the
 * Discover network switch never reads as on before the saved choice arrives.
 */
class SettingsController(
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    /** Null hides the Storage card. */
    private val storage: StorageControls? = null,
) {
    val prefs: StateFlow<AppPrefs?> = repository.prefs.stateIn(scope, SharingStarted.Eagerly, null)

    private val _storageUsage = MutableStateFlow<StorageUsage?>(null)

    /** Null until measured once, or when the host has no [StorageControls]. */
    val storageUsage: StateFlow<StorageUsage?> = _storageUsage.asStateFlow()

    /** Whether a clear is running, so the card disables its buttons. */
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    val hasStorage: Boolean get() = storage != null

    fun refreshStorage() {
        val controls = storage ?: return
        scope.launch { measure(controls) }
    }

    fun clearHistoryCache() = clear { it.clearHistoryCache() }

    fun clearMapCache() = clear { it.clearMapCache() }

    private fun clear(action: suspend (StorageControls) -> Unit) {
        val controls = storage ?: return
        if (_clearing.value) return
        _clearing.value = true
        scope.launch {
            try {
                action(controls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cache that could not be cleared keeps its size, the card shows it as it is.
            } finally {
                _clearing.value = false
                measure(controls)
            }
        }
    }

    private suspend fun measure(controls: StorageControls) {
        try {
            _storageUsage.value = controls.usage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the last measurement.
        }
    }

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
