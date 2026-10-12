package earth.walkthru.aqsync.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import earth.walkthru.aqsync.storage.RetentionPolicy
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * The app preferences on one Preferences DataStore, shared by Android and iOS. [prefs] emits the
 * current [AppPrefs], the setters write one key each. Keep one instance per file per process
 * (DataStore's rule): Hilt provides a singleton on Android, `AppRoot` holds one on iOS.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val prefs: Flow<AppPrefs> = store.data.map { it.toAppPrefs() }.distinctUntilChanged()

    val detailLevel: Flow<DetailLevel> = prefs.map { it.detailLevel }.distinctUntilChanged()
    val clockSyncMode: Flow<ClockSyncMode> = prefs.map { it.clockSyncMode }.distinctUntilChanged()
    val showPublicSensors: Flow<Boolean> = prefs.map { it.showPublicSensors }.distinctUntilChanged()
    val unitSystem: Flow<UnitSystem> = prefs.map { it.unitSystem }.distinctUntilChanged()
    val retention: Flow<RetentionPolicy> = prefs.map { it.retention }.distinctUntilChanged()
    val discoverSource: Flow<DiscoverSource> = prefs.map { it.discoverSource }.distinctUntilChanged()

    /** The stored values once, for callers outside a flow. */
    suspend fun current(): AppPrefs = prefs.first()

    /** Also writes the legacy `advanced_mode`, so an older build reading the file agrees. */
    suspend fun setDetailLevel(level: DetailLevel) {
        store.edit {
            it[SettingsKeys.DETAIL_LEVEL] = level.name
            it[SettingsKeys.ADVANCED_MODE] = level.developer
        }
    }

    suspend fun setClockSyncMode(mode: ClockSyncMode) {
        store.edit { it[SettingsKeys.CLOCK_SYNC_MODE] = mode.name }
    }

    suspend fun setShowPublicSensors(on: Boolean) {
        store.edit { it[SettingsKeys.SHOW_PUBLIC_SENSORS] = on }
    }

    suspend fun setUnitSystem(system: UnitSystem) {
        store.edit { it[SettingsKeys.UNIT_SYSTEM] = system.name }
    }

    suspend fun setRetention(policy: RetentionPolicy) {
        store.edit {
            it[SettingsKeys.DERIVED_QUOTA_BYTES] = policy.derivedQuotaBytes
            it[SettingsKeys.MAP_CACHE_BYTES] = policy.mapCacheBytes
        }
    }

    suspend fun setDiscoverSource(source: DiscoverSource) {
        store.edit { it[SettingsKeys.DISCOVER_SOURCE] = source.name }
    }
}

/** Reads [AppPrefs] with the defaults and the legacy `advanced_mode` rule. Unknown names read as the default. */
fun Preferences.toAppPrefs(): AppPrefs {
    val defaults = AppPrefs()
    val level = this[SettingsKeys.DETAIL_LEVEL]?.let { name -> DetailLevel.entries.firstOrNull { it.name == name } }
        ?: if (this[SettingsKeys.ADVANCED_MODE] == true) DetailLevel.DEVELOPER else defaults.detailLevel
    return AppPrefs(
        detailLevel = level,
        clockSyncMode = this[SettingsKeys.CLOCK_SYNC_MODE]?.let { name -> ClockSyncMode.entries.firstOrNull { it.name == name } }
            ?: defaults.clockSyncMode,
        showPublicSensors = this[SettingsKeys.SHOW_PUBLIC_SENSORS] ?: defaults.showPublicSensors,
        unitSystem = UnitSystem.parse(this[SettingsKeys.UNIT_SYSTEM]),
        retention = RetentionPolicy(
            derivedQuotaBytes = this[SettingsKeys.DERIVED_QUOTA_BYTES]?.takeIf { it >= 0 } ?: defaults.retention.derivedQuotaBytes,
            mapCacheBytes = this[SettingsKeys.MAP_CACHE_BYTES]?.takeIf { it >= 0 } ?: defaults.retention.mapCacheBytes,
        ),
        discoverSource = this[SettingsKeys.DISCOVER_SOURCE]?.let { name -> DiscoverSource.entries.firstOrNull { it.name == name } }
            ?: defaults.discoverSource,
    )
}

/**
 * The settings DataStore at [absolutePath], which ends in [SettingsKeys.RELATIVE_PATH]. A damaged
 * file reads as empty (every default) instead of failing the app. [migrations] run once before the
 * first read (iOS imports its old UserDefaults values this way).
 */
fun createSettingsDataStore(
    absolutePath: String,
    migrations: List<DataMigration<Preferences>> = emptyList(),
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    migrations = migrations,
    produceFile = { absolutePath.toPath() },
)
