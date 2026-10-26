package space.opensensor.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import space.opensensor.units.UnitSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserDefaultsSettingsMigrationTest {
    private val suite = "space.opensensor.settings-test"
    private val defaults = NSUserDefaults(suiteName = suite)

    @AfterTest
    fun clear() {
        defaults.removePersistentDomainForName(suite)
    }

    @Test
    fun importsTheTwoOldIosSettingsOnceAndRemovesThem() = runTest {
        defaults.setBool(false, UserDefaultsSettingsMigration.LEGACY_SHOW_PUBLIC)
        defaults.setObject("IMPERIAL", UserDefaultsSettingsMigration.LEGACY_UNIT_SYSTEM)
        val migration = UserDefaultsSettingsMigration(defaults)

        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val prefs = migration.migrate(emptyPreferences()).toAppPrefs()
        assertEquals(false, prefs.showPublicSensors)
        assertEquals(UnitSystem.IMPERIAL, prefs.unitSystem)

        migration.cleanUp()
        assertNull(defaults.objectForKey(UserDefaultsSettingsMigration.LEGACY_SHOW_PUBLIC))
        assertNull(defaults.objectForKey(UserDefaultsSettingsMigration.LEGACY_UNIT_SYSTEM))
        assertFalse(migration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun aValueAlreadyInTheStoreWins() = runTest {
        defaults.setBool(false, UserDefaultsSettingsMigration.LEGACY_SHOW_PUBLIC)
        val current = mutablePreferencesOf(SettingsKeys.SHOW_PUBLIC_SENSORS to true)
        val prefs = UserDefaultsSettingsMigration(defaults).migrate(current).toAppPrefs()
        assertEquals(true, prefs.showPublicSensors)
    }

    @Test
    fun theFileLivesInApplicationSupport() {
        val path = iosSettingsFilePath()
        assertTrue(path.endsWith("Application Support/datastore/settings.preferences_pb"), path)
    }
}
