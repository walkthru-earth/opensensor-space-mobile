package space.opensensor.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.opensensor.settings.AppPrefs
import space.opensensor.settings.DetailLevel
import space.opensensor.settings.SettingsRepository
import space.opensensor.units.UnitSystem

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
    /** The host logger. Gets the operation and the exception class name, never a path or an id. */
    private val log: (String) -> Unit = {},
) {
    val prefs: StateFlow<AppPrefs?> = repository.prefs.stateIn(scope, SharingStarted.Eagerly, null)

    private val _storageUsage = MutableStateFlow<StorageUsage?>(null)

    /** Null until measured once, or when the host has no [StorageControls]. */
    val storageUsage: StateFlow<StorageUsage?> = _storageUsage.asStateFlow()

    /** Whether a clear is running, so the card disables its buttons. */
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    val hasStorage: Boolean get() = storage != null

    private val _removedFiles = MutableStateFlow(0)

    /** Files removed from this phone that sync skips, measured with [storageUsage]. */
    val removedFiles: StateFlow<Int> = _removedFiles.asStateFlow()

    fun refreshStorage() {
        val controls = storage ?: return
        scope.launch { measure(controls) }
    }

    fun clearHistoryCache() = clear { it.clearHistoryCache() }

    fun clearMapCache() = clear { it.clearMapCache() }

    /** Lets the next sync download the removed files again, after the user confirmed. */
    fun downloadRemovedAgain() = clear { it.downloadRemovedAgain() }

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
                log("settings: clear failed: ${e::class.simpleName}")
            } finally {
                _clearing.value = false
                measure(controls)
            }
        }
    }

    private val _archiveDays = MutableStateFlow<OldFilesRange?>(null)

    /** The UTC days the phone archive spans, measured with [storageUsage]. Null offers every date. */
    val archiveDays: StateFlow<OldFilesRange?> = _archiveDays.asStateFlow()

    private val _oldFilesRange = MutableStateFlow<OldFilesRange?>(null)

    /** The UTC days the old format cleanup looks at. Null means every date. */
    val oldFilesRange: StateFlow<OldFilesRange?> = _oldFilesRange.asStateFlow()

    private val _oldFiles = MutableStateFlow<OldFilesState>(OldFilesState.Idle)

    /** The old format cleanup, a dry run first, then a confirmed removal. */
    val oldFiles: StateFlow<OldFilesState> = _oldFiles.asStateFlow()

    /** A new range drops the last count, so a removal always matches what the user saw. */
    fun setOldFilesRange(range: OldFilesRange?) {
        if (_oldFiles.value.busy) return
        _oldFilesRange.value = range
        _oldFiles.value = OldFilesState.Idle
    }

    /** The dry run. Counts the old format files in [oldFilesRange] and changes nothing. */
    fun findOldFiles() {
        val controls = storage ?: return
        if (_oldFiles.value.busy || _clearing.value) return
        val range = _oldFilesRange.value
        _oldFiles.value = OldFilesState.Finding
        scope.launch {
            _oldFiles.value = try {
                OldFilesState.Found(controls.findOldFiles(range))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("settings: find old files failed: ${e::class.simpleName}")
                OldFilesState.Failed
            }
        }
    }

    /** Removes the files of the last dry run, after the user confirmed. A sync may run meanwhile. */
    fun removeOldFiles() {
        val controls = storage ?: return
        val found = _oldFiles.value as? OldFilesState.Found ?: return
        if (found.plan.files == 0 || _clearing.value) return
        _oldFiles.value = OldFilesState.Removing(found.plan)
        _clearing.value = true
        scope.launch {
            try {
                _oldFiles.value = OldFilesState.Removed(controls.removeOldFiles(found.plan))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("settings: remove old files failed: ${e::class.simpleName}")
                _oldFiles.value = OldFilesState.RemoveFailed
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
            log("settings: storage usage failed: ${e::class.simpleName}")
        }
        try {
            _removedFiles.value = controls.removedFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("settings: removed files count failed: ${e::class.simpleName}")
        }
        try {
            _archiveDays.value = controls.archiveDays()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("settings: archive days failed: ${e::class.simpleName}")
        }
    }

    fun setDetailLevel(level: DetailLevel) {
        scope.launch { repository.setDetailLevel(level) }
    }

    fun setShowPublicSensors(on: Boolean) {
        scope.launch { repository.setShowPublicSensors(on) }
    }

    fun setUnitSystem(system: UnitSystem) {
        scope.launch { repository.setUnitSystem(system) }
    }
}

/** The steps of the old format cleanup in the Storage card. */
sealed interface OldFilesState {
    /** Nothing counted yet. */
    data object Idle : OldFilesState

    data object Finding : OldFilesState

    data class Found(val plan: OldFilesPlan) : OldFilesState

    data class Removing(val plan: OldFilesPlan) : OldFilesState

    data class Removed(val result: OldFilesResult) : OldFilesState

    /** The count threw. Nothing changed. */
    data object Failed : OldFilesState

    /** The removal threw. Files that were not reached stay. */
    data object RemoveFailed : OldFilesState

    val busy: Boolean get() = this is Finding || this is Removing
}
