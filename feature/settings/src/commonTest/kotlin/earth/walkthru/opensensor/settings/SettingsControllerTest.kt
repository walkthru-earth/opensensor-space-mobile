package earth.walkthru.opensensor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import earth.walkthru.aqsync.settings.AppPrefs
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : DataStore<Preferences> {
    val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** Storage that shrinks what each clear names, and can hold a clear open or make it fail. */
private class FakeStorage : StorageControls {
    var usage = StorageUsage(sensorFilesBytes = 40, historyCacheBytes = 30, mapCacheBytes = 20, temporaryBytes = 10)
    var gate: CompletableDeferred<Unit>? = null
    var failClear = false
    val calls = mutableListOf<String>()

    override suspend fun usage(): StorageUsage = usage

    override suspend fun clearHistoryCache() {
        calls += "history"
        gate?.await()
        if (failClear) error("busy")
        usage = usage.copy(historyCacheBytes = 0)
    }

    override suspend fun clearMapCache() {
        calls += "map"
        gate?.await()
        if (failClear) error("busy")
        usage = usage.copy(mapCacheBytes = 0)
    }
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
    fun storageIsMeasuredAndEachClearTouchesOnlyItsCache() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        assertTrue(controller.hasStorage)
        assertNull(controller.storageUsage.value)

        controller.refreshStorage()
        assertEquals(storage.usage, controller.storageUsage.value)

        controller.clearHistoryCache()
        assertEquals(StorageUsage(40, 0, 20, 10), controller.storageUsage.value)
        controller.clearMapCache()
        // Sensor files never shrink from a Settings action.
        assertEquals(StorageUsage(40, 0, 0, 10), controller.storageUsage.value)
        assertEquals(listOf("history", "map"), storage.calls)
    }

    @Test
    fun aRunningClearBlocksAnotherAndAFailureKeepsTheSize() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { gate = CompletableDeferred() }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)

        controller.clearHistoryCache()
        assertTrue(controller.clearing.value)
        controller.clearMapCache()
        assertEquals(listOf("history"), storage.calls)

        storage.failClear = true
        storage.gate!!.complete(Unit)
        assertFalse(controller.clearing.value)
        assertEquals(30, controller.storageUsage.value?.historyCacheBytes)
    }

    @Test
    fun noStorageControlsHidesTheCard() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()))
        assertFalse(controller.hasStorage)
        controller.refreshStorage()
        controller.clearHistoryCache()
        assertNull(controller.storageUsage.value)
    }

    @Test
    fun megabytesShowOneDecimal() {
        assertEquals("0.0", megabytes(0))
        assertEquals("1.5", megabytes(1_572_864))
    }

    @Test
    fun everyChoiceHasALabel() {
        // A new enum value without copy fails here before it reaches a screen.
        DetailLevel.entries.forEach { levelLabel(it); levelCaption(it) }
        ClockSyncMode.entries.forEach { clockLabel(it); clockCaption(it) }
        UnitSystem.entries.forEach { unitLabel(it) }
    }
}
