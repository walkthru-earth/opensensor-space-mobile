package space.opensensor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.storage.RetentionPolicy
import space.opensensor.units.UnitSystem
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
            listOf(
                "detail_level",
                "advanced_mode",
                "show_public_sensors",
                "unit_system",
                "derived_quota_bytes",
                "map_cache_bytes",
                "discover_source",
            ),
            SettingsKeys.ALL.map { it.name },
        )
        assertEquals("datastore/settings.preferences_pb", SettingsKeys.RELATIVE_PATH)
    }

    @Test
    fun emptyStoreReadsTheDefaults() = runTest {
        val prefs = SettingsRepository(MemoryStore()).current()
        assertEquals(AppPrefs(DetailLevel.SIMPLE, showPublicSensors = true, unitSystem = UnitSystem.AUTO), prefs)
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
        repo.setShowPublicSensors(false)
        repo.setUnitSystem(UnitSystem.IMPERIAL)
        assertEquals(AppPrefs(DetailLevel.SIMPLE, showPublicSensors = false, unitSystem = UnitSystem.IMPERIAL), repo.current())
        assertEquals("IMPERIAL", store.state.value[SettingsKeys.UNIT_SYSTEM])
    }

    @Test
    fun unknownNamesFallBackToDefaults() = runTest {
        val prefs = mutablePreferencesOf(
            SettingsKeys.DETAIL_LEVEL to "EXPERT",
            SettingsKeys.UNIT_SYSTEM to "furlongs",
            SettingsKeys.DISCOVER_SOURCE to "MARS",
        )
        assertEquals(AppPrefs(), SettingsRepository(MemoryStore(prefs)).current())
    }

    @Test
    fun retentionPersistsAcrossInstances() = runTest {
        val store = MemoryStore()
        SettingsRepository(store).setRetention(RetentionPolicy(derivedQuotaBytes = 8L * 1024 * 1024, mapCacheBytes = 0))
        // A new repository over the same bytes is the next process start.
        val restarted = SettingsRepository(MemoryStore(store.state.value))
        assertEquals(RetentionPolicy(8L * 1024 * 1024, 0), restarted.current().retention)
        assertEquals(8L * 1024 * 1024, store.state.value[SettingsKeys.DERIVED_QUOTA_BYTES])
    }

    @Test
    fun missingOrNegativeQuotasReadAsTheDefault() = runTest {
        assertEquals(RetentionPolicy(), SettingsRepository(MemoryStore()).current().retention)
        val bad = mutablePreferencesOf(SettingsKeys.DERIVED_QUOTA_BYTES to -1L, SettingsKeys.MAP_CACHE_BYTES to 5L)
        assertEquals(RetentionPolicy(RetentionPolicy.DEFAULT_DERIVED_QUOTA_BYTES, 5L), SettingsRepository(MemoryStore(bad)).current().retention)
    }

    @Test
    fun discoverSourceDefaultsToSampleAndRoundTrips() = runTest {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        assertEquals(DiscoverSource.SAMPLE, repo.current().discoverSource)
        repo.setDiscoverSource(DiscoverSource.OPENAQ)
        assertEquals(DiscoverSource.OPENAQ, repo.current().discoverSource)
        assertEquals(DiscoverSource.OPENAQ, repo.discoverSource.first())
        assertEquals("OPENAQ", store.state.value[SettingsKeys.DISCOVER_SOURCE])
    }

    @Test
    fun unknownDiscoverSourceReadsSample() = runTest {
        val prefs = mutablePreferencesOf(SettingsKeys.DISCOVER_SOURCE to "openaq-v9")
        assertEquals(DiscoverSource.SAMPLE, SettingsRepository(MemoryStore(prefs)).current().discoverSource)
    }

    @Test
    fun effectiveSourceIsSampleWhenPublicSensorsAreOff() {
        assertEquals(DiscoverSource.SAMPLE, effectiveDiscoverSource(DiscoverSource.OPENAQ, showPublicSensors = false))
        assertEquals(DiscoverSource.OPENAQ, effectiveDiscoverSource(DiscoverSource.OPENAQ, showPublicSensors = true))
        assertEquals(DiscoverSource.SAMPLE, effectiveDiscoverSource(DiscoverSource.SAMPLE, showPublicSensors = true))
        assertEquals(DiscoverSource.SAMPLE, effectiveDiscoverSource(DiscoverSource.SAMPLE, showPublicSensors = false))
    }

    @Test
    fun nicknamesAndPendingSetupStayPerSensor() = runTest {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        repo.setNickname("AA:BB:CC:DD:EE:FF", "  Balcony  ")
        repo.setSetupPending("AA:BB:CC:DD:EE:FF", true)
        repo.setSetupPending("11:22:33:44:55:66", true)
        assertEquals(mapOf("aa:bb:cc:dd:ee:ff" to "Balcony"), repo.nicknames.first())
        assertEquals(setOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"), repo.setupPending.first())
        assertEquals(AppPrefs(), repo.current())

        repo.setSetupPending("11:22:33:44:55:66", false)
        repo.forgetSensor("aa:bb:cc:dd:ee:ff")
        assertEquals(emptyMap(), repo.nicknames.first())
        assertEquals(emptySet(), repo.setupPending.first())

        repo.setNickname("x", "Kitchen")
        repo.setNickname("x", " ")
        assertEquals(emptyMap(), repo.nicknames.first())
    }
}
