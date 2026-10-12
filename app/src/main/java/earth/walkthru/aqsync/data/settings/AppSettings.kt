package earth.walkthru.aqsync.data.settings

import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The shared detail level (`:core:settings`), under its old Android name so screens keep their imports. */
typealias DetailLevel = earth.walkthru.aqsync.settings.DetailLevel

/**
 * Android's view of the shared app preferences ([SettingsRepository], `:core:settings`). The store,
 * file, keys, defaults and the legacy `advanced_mode` rule live there, once for Android and iOS.
 * This keeps the flows Android screens already collect.
 */
@Singleton
class AppSettings @Inject constructor(private val repository: SettingsRepository) {

    val detailLevel: Flow<DetailLevel> = repository.detailLevel

    /** "Show public sensors". Off, Discover makes no network request at all (AGENTS.md network boundary). */
    val showPublicSensors: Flow<Boolean> = repository.showPublicSensors

    suspend fun setShowPublicSensors(on: Boolean) = repository.setShowPublicSensors(on)

    /** Which public feed Discover reads. Combine with [showPublicSensors] through `effectiveDiscoverSource`. */
    val discoverSource: Flow<earth.walkthru.aqsync.settings.DiscoverSource> = repository.discoverSource

    suspend fun setDiscoverSource(source: earth.walkthru.aqsync.settings.DiscoverSource) = repository.setDiscoverSource(source)

    /** Display units, Automatic by default. Display only, the archive and exports stay SI. */
    val unitSystem: Flow<UnitSystem> = repository.unitSystem

    suspend fun setUnitSystem(system: UnitSystem) = repository.setUnitSystem(system)

    /** Detailed or Developer. */
    val detailed: Flow<Boolean> = detailLevel.map { it.detailed }

    /** Developer only, what the old Advanced switch meant. The Debug destination, raw views. */
    val advancedMode: Flow<Boolean> = detailLevel.map { it.developer }

    suspend fun setDetailLevel(level: DetailLevel) = repository.setDetailLevel(level)
}
