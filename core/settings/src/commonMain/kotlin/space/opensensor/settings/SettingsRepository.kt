package space.opensensor.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import space.opensensor.storage.RetentionPolicy
import space.opensensor.units.UnitSystem

/**
 * The app preferences on one Preferences DataStore, shared by Android and iOS. [prefs] emits the
 * current [AppPrefs], the setters write one key each. Keep one instance per file per process
 * (DataStore's rule): Hilt provides a singleton on Android, `AppRoot` holds one on iOS.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val prefs: Flow<AppPrefs> = store.data.map { it.toAppPrefs() }.distinctUntilChanged()

    val detailLevel: Flow<DetailLevel> = prefs.map { it.detailLevel }.distinctUntilChanged()
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

    /** Phone only nicknames by lowercase sensor id. They stay on the phone, see [setNickname]. */
    val nicknames: Flow<Map<String, String>> = store.data.map { p ->
        p.asMap().entries.mapNotNull { (key, value) ->
            val id = key.name.removePrefix(SettingsKeys.NICKNAME_PREFIX)
            if (id != key.name && value is String) id to value else null
        }.toMap()
    }.distinctUntilChanged()

    /** Lowercase ids of sensors whose setup has not reached Done. */
    val setupPending: Flow<Set<String>> = store.data.map { it[SettingsKeys.SETUP_PENDING].orEmpty() }.distinctUntilChanged()

    /**
     * Stores the nickname of sensor [id], or removes it when [nickname] is blank. The caller checks
     * the length. It is never put in a frame, a patch or a log.
     */
    suspend fun setNickname(id: String, nickname: String?) {
        val key = SettingsKeys.nickname(id)
        val value = nickname?.trim().orEmpty()
        store.edit { if (value.isEmpty()) it.remove(key) else it[key] = value }
    }

    suspend fun setSetupPending(id: String, pending: Boolean) {
        val lower = id.lowercase()
        store.edit {
            val now = it[SettingsKeys.SETUP_PENDING].orEmpty()
            it[SettingsKeys.SETUP_PENDING] = if (pending) now + lower else now - lower
        }
    }

    /** Drops the nickname and the setup flag of a forgotten sensor. */
    suspend fun forgetSensor(id: String) {
        val lower = id.lowercase()
        store.edit {
            it.remove(SettingsKeys.nickname(lower))
            it[SettingsKeys.SETUP_PENDING] = it[SettingsKeys.SETUP_PENDING].orEmpty() - lower
        }
    }
}

/** Reads [AppPrefs] with the defaults and the legacy `advanced_mode` rule. Unknown names read as the default. */
fun Preferences.toAppPrefs(): AppPrefs {
    val defaults = AppPrefs()
    val level = this[SettingsKeys.DETAIL_LEVEL]?.let { name -> DetailLevel.entries.firstOrNull { it.name == name } }
        ?: if (this[SettingsKeys.ADVANCED_MODE] == true) DetailLevel.DEVELOPER else defaults.detailLevel
    return AppPrefs(
        detailLevel = level,
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
