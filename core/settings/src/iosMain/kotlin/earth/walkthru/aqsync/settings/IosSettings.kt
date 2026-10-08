package earth.walkthru.aqsync.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * The settings file on iOS, `Application Support/datastore/settings.preferences_pb`. Application
 * Support is private to the app, hidden from the Files app and backed up, as settings should be.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosSettingsFilePath(): String {
    val base: NSURL = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ),
    ) { "Application Support is unavailable" }
    return requireNotNull(base.URLByAppendingPathComponent(SettingsKeys.RELATIVE_PATH)?.path) { "Bad settings path" }
}

/** The one settings repository for the iOS app, with the old UserDefaults values imported once. */
fun iosSettingsRepository(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults): SettingsRepository =
    SettingsRepository(createSettingsDataStore(iosSettingsFilePath(), listOf(UserDefaultsSettingsMigration(defaults))))

/**
 * Imports the two settings iOS kept in UserDefaults before the shared store (`discover.showPublicSensors`
 * and `settings.unitSystem`), then removes them. A key already in the store wins.
 */
class UserDefaultsSettingsMigration(private val defaults: NSUserDefaults) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        defaults.objectForKey(LEGACY_SHOW_PUBLIC) != null || defaults.objectForKey(LEGACY_UNIT_SYSTEM) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val out = currentData.toMutablePreferences()
        if (defaults.objectForKey(LEGACY_SHOW_PUBLIC) != null && SettingsKeys.SHOW_PUBLIC_SENSORS !in currentData) {
            out[SettingsKeys.SHOW_PUBLIC_SENSORS] = defaults.boolForKey(LEGACY_SHOW_PUBLIC)
        }
        val units = defaults.stringForKey(LEGACY_UNIT_SYSTEM)
        if (units != null && SettingsKeys.UNIT_SYSTEM !in currentData) {
            out[SettingsKeys.UNIT_SYSTEM] = units
        }
        return out.toPreferences()
    }

    override suspend fun cleanUp() {
        defaults.removeObjectForKey(LEGACY_SHOW_PUBLIC)
        defaults.removeObjectForKey(LEGACY_UNIT_SYSTEM)
    }

    companion object {
        const val LEGACY_SHOW_PUBLIC: String = "discover.showPublicSensors"
        const val LEGACY_UNIT_SYSTEM: String = "settings.unitSystem"
    }
}
