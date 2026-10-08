package earth.walkthru.opensensor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import earth.walkthru.aqsync.settings.AppPrefs
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class MemoryStore : DataStore<Preferences> {
    val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    @Test
    fun actionsReachTheStoreAndComeBackAsState() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()))
        assertEquals(AppPrefs(), controller.prefs.value)

        controller.setDetailLevel(DetailLevel.DETAILED)
        controller.setClockSyncMode(ClockSyncMode.AUTOMATIC)
        controller.setShowPublicSensors(false)
        controller.setUnitSystem(UnitSystem.METRIC)
        assertEquals(AppPrefs(DetailLevel.DETAILED, ClockSyncMode.AUTOMATIC, showPublicSensors = false, unitSystem = UnitSystem.METRIC), controller.prefs.value)
    }

    @Test
    fun everyChoiceHasALabel() {
        // A new enum value without copy fails here before it reaches a screen.
        DetailLevel.entries.forEach { levelLabel(it); levelCaption(it) }
        ClockSyncMode.entries.forEach { clockLabel(it); clockCaption(it) }
        UnitSystem.entries.forEach { unitLabel(it) }
    }
}
