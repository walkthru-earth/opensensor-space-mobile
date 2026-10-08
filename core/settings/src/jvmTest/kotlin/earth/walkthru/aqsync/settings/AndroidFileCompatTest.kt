package earth.walkthru.aqsync.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An Android user upgrading keeps their settings. The old app wrote the file with
 * `preferencesDataStore("settings")`, which is `PreferenceDataStoreFactory.create` over a
 * `java.io.File` at `files/datastore/settings.preferences_pb`. The shared store must read that file.
 */
class AndroidFileCompatTest {
    @Test
    fun theSharedStoreReadsWhatTheOldAndroidStoreWrote() = runBlocking {
        val filesDir = Files.createTempDirectory("settings-compat").toFile()
        val file = File(filesDir, SettingsKeys.RELATIVE_PATH)

        val oldScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val old = PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { file })
        old.edit {
            it[SettingsKeys.DETAIL_LEVEL] = "DETAILED"
            it[SettingsKeys.ADVANCED_MODE] = false
            it[SettingsKeys.CLOCK_SYNC_MODE] = "AUTOMATIC"
            it[SettingsKeys.SHOW_PUBLIC_SENSORS] = false
            it[SettingsKeys.UNIT_SYSTEM] = "IMPERIAL"
        }
        oldScope.cancel()

        val shared = SettingsRepository(createSettingsDataStore(file.absolutePath))
        assertEquals(
            AppPrefs(DetailLevel.DETAILED, ClockSyncMode.AUTOMATIC, showPublicSensors = false, unitSystem = UnitSystem.IMPERIAL),
            shared.current(),
        )
        filesDir.deleteRecursively()
        Unit
    }
}
