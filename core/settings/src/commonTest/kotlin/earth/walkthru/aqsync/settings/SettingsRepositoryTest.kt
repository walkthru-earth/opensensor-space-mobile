package earth.walkthru.aqsync.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An in-memory DataStore, so the repository logic runs the same on the JVM and the iOS simulator. */
private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

class SettingsRepositoryTest {
    @Test
    fun keysAreAndroidsNames() {
        // Golden list. Renaming any of these drops every Android user's saved choice.
        assertEquals(
            listOf("detail_level", "advanced_mode", "clock_sync_mode", "show_public_sensors", "unit_system"),
            SettingsKeys.ALL.map { it.name },
        )
        assertEquals("datastore/settings.preferences_pb", SettingsKeys.RELATIVE_PATH)
    }

    @Test
    fun emptyStoreReadsTheDefaults() = runTest {
        val prefs = SettingsRepository(MemoryStore()).current()
        assertEquals(AppPrefs(DetailLevel.SIMPLE, ClockSyncMode.ASK, showPublicSensors = true, unitSystem = UnitSystem.AUTO), prefs)
    }

    @Test
    fun legacyAdvancedModeReadsAsDeveloperUntilALevelIsStored() = runTest {
        val legacy = SettingsRepository(MemoryStore(mutablePreferencesOf(SettingsKeys.ADVANCED_MODE to true)))
        assertEquals(DetailLevel.DEVELOPER, legacy.current().detailLevel)
        legacy.setDetailLevel(DetailLevel.DETAILED)
        assertEquals(DetailLevel.DETAILED, legacy.current().detailLevel)
    }

    @Test
    fun setterWritesTheLegacySwitchToo() = runTest {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        repo.setDetailLevel(DetailLevel.DEVELOPER)
        assertEquals(true, store.state.value[SettingsKeys.ADVANCED_MODE])
        assertEquals("DEVELOPER", store.state.value[SettingsKeys.DETAIL_LEVEL])
        repo.setDetailLevel(DetailLevel.SIMPLE)
        assertEquals(false, store.state.value[SettingsKeys.ADVANCED_MODE])
    }

    @Test
    fun everySetterRoundTrips() = runTest {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        repo.setClockSyncMode(ClockSyncMode.NEVER)
        repo.setShowPublicSensors(false)
        repo.setUnitSystem(UnitSystem.IMPERIAL)
        assertEquals(AppPrefs(DetailLevel.SIMPLE, ClockSyncMode.NEVER, showPublicSensors = false, unitSystem = UnitSystem.IMPERIAL), repo.current())
        assertEquals("NEVER", store.state.value[SettingsKeys.CLOCK_SYNC_MODE])
        assertEquals("IMPERIAL", store.state.value[SettingsKeys.UNIT_SYSTEM])
    }

    @Test
    fun unknownNamesFallBackToDefaults() = runTest {
        val prefs = mutablePreferencesOf(
            SettingsKeys.DETAIL_LEVEL to "EXPERT",
            SettingsKeys.CLOCK_SYNC_MODE to "SOMETIMES",
            SettingsKeys.UNIT_SYSTEM to "furlongs",
        )
        assertEquals(AppPrefs(), SettingsRepository(MemoryStore(prefs)).current())
    }
}
