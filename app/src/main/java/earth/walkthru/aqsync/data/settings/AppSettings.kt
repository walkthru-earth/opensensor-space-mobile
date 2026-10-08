package earth.walkthru.aqsync.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * How much the UI shows. Cosmetic only: every level reads the same data; higher levels *reveal*,
 * nothing the Simple experience needs depends on them (AGENTS.md).
 */
enum class DetailLevel {
    /** The current reading and today, in plain words. History and files stay one tap away. */
    SIMPLE,

    /** Every measurement, storage counters, the Files chart and Metrics inspector. The History source card is Developer. */
    DETAILED,

    /** Detailed plus the Debug screen (raw status JSON, frames, serial log, raw commands). */
    DEVELOPER,
    ;

    val detailed: Boolean get() = this != SIMPLE
    val developer: Boolean get() = this == DEVELOPER
}

/**
 * App-level preferences (DataStore Preferences): the **detail level** (Simple · Detailed · Developer).
 * Nothing here is a device setting — those live on the device and are read/written with
 * `GET_CONFIG` / `SET_CONFIG`.
 *
 * The level replaced a Boolean "Advanced mode" (2026-09-17): an existing `advanced_mode = true` is
 * read as DEVELOPER until the user picks a level, so nobody loses their Debug screen.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val detailLevel: Flow<DetailLevel> = context.settingsStore.data.map { prefs ->
        prefs[DETAIL_LEVEL]?.let { name -> DetailLevel.entries.firstOrNull { it.name == name } }
            ?: if (prefs[ADVANCED_MODE] == true) DetailLevel.DEVELOPER else DetailLevel.SIMPLE
    }

    /** Consent policy for SET_TIME during an explicit Smart Sync. Default asks first. */
    val clockSyncMode: Flow<ClockSyncMode> = context.settingsStore.data.map { prefs ->
        prefs[CLOCK_SYNC_MODE]?.let { name -> ClockSyncMode.entries.firstOrNull { it.name == name } } ?: ClockSyncMode.ASK
    }

    /**
     * "Show public sensors". On by default. Off, Discover makes no network request at all (no
     * basemap, no public feed) and shows the bundled sample on a plain background
     * (AGENTS.md network boundary).
     */
    val showPublicSensors: Flow<Boolean> = context.settingsStore.data.map { prefs -> prefs[SHOW_PUBLIC_SENSORS] ?: true }

    suspend fun setShowPublicSensors(on: Boolean) {
        context.settingsStore.edit { it[SHOW_PUBLIC_SENSORS] = on }
    }

    /**
     * Display units, Automatic (the phone's region) by default. Display only, the archive, exports
     * and the Discover scale stay SI (`:core:units`).
     */
    val unitSystem: Flow<UnitSystem> = context.settingsStore.data.map { prefs -> UnitSystem.parse(prefs[UNIT_SYSTEM]) }

    suspend fun setUnitSystem(system: UnitSystem) {
        context.settingsStore.edit { it[UNIT_SYSTEM] = system.name }
    }

    /** Detailed or Developer. */
    val detailed: Flow<Boolean> = detailLevel.map { it.detailed }

    /** Developer only — what the old Advanced switch meant: the Debug destination, raw views. */
    val advancedMode: Flow<Boolean> = detailLevel.map { it.developer }

    suspend fun setDetailLevel(level: DetailLevel) {
        context.settingsStore.edit {
            it[DETAIL_LEVEL] = level.name
            it[ADVANCED_MODE] = level.developer
        }
    }

    suspend fun setClockSyncMode(mode: ClockSyncMode) {
        context.settingsStore.edit { it[CLOCK_SYNC_MODE] = mode.name }
    }

    /** Kept for callers that only know the old switch: on → Developer, off → Simple. */
    suspend fun setAdvancedMode(on: Boolean) = setDetailLevel(if (on) DetailLevel.DEVELOPER else DetailLevel.SIMPLE)

    private companion object {
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
        val DETAIL_LEVEL = stringPreferencesKey("detail_level")
        val CLOCK_SYNC_MODE = stringPreferencesKey("clock_sync_mode")
        val SHOW_PUBLIC_SENSORS = booleanPreferencesKey("show_public_sensors")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    }
}
