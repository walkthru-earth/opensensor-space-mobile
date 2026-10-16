package earth.walkthru.opensensor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import earth.walkthru.aqsync.settings.AppPrefs
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
import kotlin.test.assertIs
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

    var syncing = false
    var oldBytes = 15L
    var oldFiles = 3
    var failFind = false
    val ranges = mutableListOf<OldFilesRange?>()
    val removed = mutableListOf<OldFilesPlan>()

    override fun syncRunning(): Boolean = syncing

    override suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan {
        ranges += range
        gate?.await()
        if (failFind) error("unreadable")
        return OldFilesPlan(oldFiles, oldBytes, stations = 1, range = range, handle = "plan-${ranges.size}")
    }

    override suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult {
        removed += plan
        gate?.await()
        usage = usage.copy(sensorFilesBytes = usage.sensorFilesBytes - plan.bytes)
        return OldFilesResult(plan.files, plan.bytes, kept = 0, syncRunning = false)
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
        controller.setShowPublicSensors(false)
        controller.setUnitSystem(UnitSystem.METRIC)
        assertEquals(AppPrefs(DetailLevel.DETAILED, showPublicSensors = false, unitSystem = UnitSystem.METRIC), controller.prefs.value)
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
        // Sensor files never shrink from a cache clear.
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
    fun aDryRunThenAConfirmedRemovalRemovesWhatWasCounted() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.refreshStorage()

        // Remove before any dry run does nothing.
        controller.removeOldFiles()
        assertTrue(storage.removed.isEmpty())

        val range = OldFilesRange(20_000, 20_010)
        controller.setOldFilesRange(range)
        controller.findOldFiles()
        val found = assertIs<OldFilesState.Found>(controller.oldFiles.value)
        assertEquals(listOf<OldFilesRange?>(range), storage.ranges)
        assertEquals(3, found.plan.files)
        // The dry run changes nothing.
        assertEquals(40, controller.storageUsage.value?.sensorFilesBytes)

        controller.removeOldFiles()
        assertEquals(listOf(found.plan), storage.removed)
        assertEquals(OldFilesState.Removed(OldFilesResult(3, 15, 0, false)), controller.oldFiles.value)
        assertEquals(25, controller.storageUsage.value?.sensorFilesBytes)
        assertFalse(controller.clearing.value)
    }

    @Test
    fun aNewRangeDropsTheLastCount() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        assertIs<OldFilesState.Found>(controller.oldFiles.value)
        controller.setOldFilesRange(OldFilesRange(1, 1))
        assertEquals(OldFilesState.Idle, controller.oldFiles.value)
        controller.removeOldFiles()
        assertTrue(storage.removed.isEmpty())
    }

    @Test
    fun removalIsRefusedWhileASyncRuns() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        storage.syncing = true
        controller.removeOldFiles()
        assertTrue(storage.removed.isEmpty())
        val done = assertIs<OldFilesState.Removed>(controller.oldFiles.value)
        assertTrue(done.result.syncRunning)
        assertEquals(3, done.result.kept)
    }

    @Test
    fun noOldFilesOffersNoRemovalAndAFailedFindIsShown() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { oldFiles = 0 }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        controller.removeOldFiles()
        assertTrue(storage.removed.isEmpty())

        storage.failFind = true
        controller.findOldFiles()
        assertEquals(OldFilesState.Failed, controller.oldFiles.value)
    }

    @Test
    fun aRunningFindBlocksAnotherAndTheRange() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { gate = CompletableDeferred() }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        assertEquals(OldFilesState.Finding, controller.oldFiles.value)
        controller.findOldFiles()
        controller.setOldFilesRange(OldFilesRange(1, 2))
        assertNull(controller.oldFilesRange.value)
        storage.gate!!.complete(Unit)
        assertEquals(1, storage.ranges.size)
        assertIs<OldFilesState.Found>(controller.oldFiles.value)
    }

    @Test
    fun epochDaysPrintAsIsoDates() {
        assertEquals("1970-01-01", isoDate(0))
        assertEquals("1969-12-31", isoDate(-1))
        assertEquals("2000-02-29", isoDate(11_016))
        assertEquals("2026-10-16", isoDate(20_742))
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
        UnitSystem.entries.forEach { unitLabel(it) }
    }
}
