package earth.walkthru.aqsync.data.settings

import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import earth.walkthru.aqsync.settings.ClockSyncMode as SharedClockSyncMode

/** The shared detail level (`:core:settings`), under its old Android name so screens keep their imports. */
typealias DetailLevel = earth.walkthru.aqsync.settings.DetailLevel

/**
 * Android's view of the shared app preferences ([SettingsRepository], `:core:settings`). The store,
 * file, keys, defaults and the legacy `advanced_mode` rule live there, once for Android and iOS.
 * This keeps the flows Android screens already collect.
 *
 * [ClockSyncMode] is still the sync engine's own enum (`data/sync`) until the engine moves to
 * `:core:sync`. The two have the same names and are mapped by name here.
 */
@Singleton
class AppSettings @Inject constructor(private val repository: SettingsRepository) {

    val detailLevel: Flow<DetailLevel> = repository.detailLevel

    /** Consent policy for SET_TIME during an explicit Smart Sync. Default asks first. */
    val clockSyncMode: Flow<ClockSyncMode> = repository.clockSyncMode.map { it.toEngine() }

    /** "Show public sensors". Off, Discover makes no network request at all (AGENTS.md network boundary). */
    val showPublicSensors: Flow<Boolean> = repository.showPublicSensors

    suspend fun setShowPublicSensors(on: Boolean) = repository.setShowPublicSensors(on)

    /** Display units, Automatic by default. Display only, the archive and exports stay SI. */
    val unitSystem: Flow<UnitSystem> = repository.unitSystem

    suspend fun setUnitSystem(system: UnitSystem) = repository.setUnitSystem(system)

    /** Detailed or Developer. */
    val detailed: Flow<Boolean> = detailLevel.map { it.detailed }

    /** Developer only, what the old Advanced switch meant. The Debug destination, raw views. */
    val advancedMode: Flow<Boolean> = detailLevel.map { it.developer }

    suspend fun setDetailLevel(level: DetailLevel) = repository.setDetailLevel(level)

    suspend fun setClockSyncMode(mode: ClockSyncMode) = repository.setClockSyncMode(mode.toShared())

    /** Kept for callers that only know the old switch. On is Developer, off is Simple. */
    suspend fun setAdvancedMode(on: Boolean) = setDetailLevel(if (on) DetailLevel.DEVELOPER else DetailLevel.SIMPLE)
}

internal fun SharedClockSyncMode.toEngine(): ClockSyncMode = ClockSyncMode.valueOf(name)

internal fun ClockSyncMode.toShared(): SharedClockSyncMode = SharedClockSyncMode.valueOf(name)
