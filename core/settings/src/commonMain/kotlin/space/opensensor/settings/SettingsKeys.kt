package space.opensensor.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * The storage contract. Android has written these names since before the shared module existed, so
 * renaming one loses every Android user's choice. `SettingsKeysTest` holds the golden list.
 */
object SettingsKeys {
    /**
     * The file, relative to the app's files directory on Android (`preferencesDataStore("settings")`
     * wrote `files/datastore/settings.preferences_pb`) and to Application Support on iOS.
     */
    const val RELATIVE_PATH: String = "datastore/settings.preferences_pb"

    /** The [DetailLevel] name. */
    val DETAIL_LEVEL: Preferences.Key<String> = stringPreferencesKey("detail_level")

    /**
     * The Boolean "Advanced mode" the level replaced (2026-09-17). Still written with the level, and
     * `true` reads as [DetailLevel.DEVELOPER] until a level is stored, so nobody loses Debug.
     */
    val ADVANCED_MODE: Preferences.Key<Boolean> = booleanPreferencesKey("advanced_mode")

    val SHOW_PUBLIC_SENSORS: Preferences.Key<Boolean> = booleanPreferencesKey("show_public_sensors")

    /** The `UnitSystem` name. Missing or unknown reads as Automatic. */
    val UNIT_SYSTEM: Preferences.Key<String> = stringPreferencesKey("unit_system")

    /** `RetentionPolicy.derivedQuotaBytes`. Missing or negative reads as the default. */
    val DERIVED_QUOTA_BYTES: Preferences.Key<Long> = longPreferencesKey("derived_quota_bytes")

    /** `RetentionPolicy.mapCacheBytes`. Missing or negative reads as the default. */
    val MAP_CACHE_BYTES: Preferences.Key<Long> = longPreferencesKey("map_cache_bytes")

    /** The [DiscoverSource] name. Missing or unknown reads as [DiscoverSource.SAMPLE]. */
    val DISCOVER_SOURCE: Preferences.Key<String> = stringPreferencesKey("discover_source")

    /**
     * Sensors added through setup whose wizard has not reached Done, by lowercase id (an Android
     * address or an iOS peripheral). The card offers Finish setup for them.
     */
    val SETUP_PENDING: Preferences.Key<Set<String>> = stringSetPreferencesKey("setup_pending")

    /** One phone only nickname per sensor, `nickname:<lowercase id>`. Never sent to the sensor. */
    const val NICKNAME_PREFIX: String = "nickname:"

    fun nickname(id: String): Preferences.Key<String> = stringPreferencesKey(NICKNAME_PREFIX + id.lowercase())

    /** The app wide keys. [SETUP_PENDING] and the per sensor nicknames are not in it. */
    val ALL: List<Preferences.Key<*>> =
        listOf(DETAIL_LEVEL, ADVANCED_MODE, SHOW_PUBLIC_SENSORS, UNIT_SYSTEM, DERIVED_QUOTA_BYTES, MAP_CACHE_BYTES, DISCOVER_SOURCE)
}
