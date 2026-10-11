package earth.walkthru.aqsync.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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

    /** The [ClockSyncMode] name. */
    val CLOCK_SYNC_MODE: Preferences.Key<String> = stringPreferencesKey("clock_sync_mode")

    val SHOW_PUBLIC_SENSORS: Preferences.Key<Boolean> = booleanPreferencesKey("show_public_sensors")

    /** The `UnitSystem` name. Missing or unknown reads as Automatic. */
    val UNIT_SYSTEM: Preferences.Key<String> = stringPreferencesKey("unit_system")

    /** The [DiscoverSource] name. Missing or unknown reads as [DiscoverSource.SAMPLE]. */
    val DISCOVER_SOURCE: Preferences.Key<String> = stringPreferencesKey("discover_source")

    val ALL: List<Preferences.Key<*>> =
        listOf(DETAIL_LEVEL, ADVANCED_MODE, CLOCK_SYNC_MODE, SHOW_PUBLIC_SENSORS, UNIT_SYSTEM, DISCOVER_SOURCE)
}
