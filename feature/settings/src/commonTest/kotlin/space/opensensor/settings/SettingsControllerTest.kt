package space.opensensor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.settings.AppPrefs
import space.opensensor.settings.DetailLevel
import space.opensensor.settings.SettingsRepository
import space.opensensor.units.UnitSystem
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

    override suspend fun usage(): StorageUsage {
        measured++
        return usage
    }

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

    var oldBytes = 15L
    var oldFiles = 3
    var failFind = false
    var failRemove = false

    /** What a removal returns. Null removes the whole plan. */
    var removeResult: OldFilesResult? = null
    var measured = 0
    val ranges = mutableListOf<OldFilesRange?>()
    val removed = mutableListOf<OldFilesPlan>()

    var archive: OldFilesRange? = null

    override suspend fun archiveDays(): OldFilesRange? = archive

    override suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan {
        ranges += range
        gate?.await()
        if (failFind) error("unreadable")
        return OldFilesPlan(oldFiles, oldBytes, stations = 1, range = range, handle = "plan-${ranges.size}")
    }

    override suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult {
        removed += plan
        gate?.await()
        if (failRemove) error("disk")
        removeResult?.let { return it }
        usage = usage.copy(sensorFilesBytes = usage.sensorFilesBytes - plan.bytes)
        pruned += plan.files
        return OldFilesResult(plan.files, plan.bytes, kept = 0)
    }

    var pruned = 0
    var failAgain = false

    override suspend fun removedFiles(): Int = pruned

    override suspend fun downloadRemovedAgain() {
        calls += "again"
        gate?.await()
        if (failAgain) error("disk")
        pruned = 0
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
        assertEquals(OldFilesState.Removed(OldFilesResult(3, 15, 0)), controller.oldFiles.value)
        assertEquals(25, controller.storageUsage.value?.sensorFilesBytes)
        assertEquals(3, controller.removedFiles.value)
        assertFalse(controller.clearing.value)
    }

    @Test
    fun downloadAgainClearsTheRemovedFilesAndCountsAgain() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { pruned = 4 }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.refreshStorage()
        assertEquals(4, controller.removedFiles.value)

        storage.gate = CompletableDeferred()
        controller.downloadRemovedAgain()
        assertTrue(controller.clearing.value)
        // A second tap while it runs does nothing.
        controller.downloadRemovedAgain()
        storage.gate!!.complete(Unit)
        assertEquals(listOf("again"), storage.calls)
        assertEquals(0, controller.removedFiles.value)
        assertFalse(controller.clearing.value)
    }

    @Test
    fun aFailedDownloadAgainKeepsTheCount() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { pruned = 2; failAgain = true }
        val logged = mutableListOf<String>()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage, log = { logged += it })
        controller.downloadRemovedAgain()
        assertEquals(2, controller.removedFiles.value)
        assertEquals(listOf("settings: clear failed: IllegalStateException"), logged)
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
    fun aRemovalThatThrowsShowsTheRemovalFailureAndMeasuresAgain() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { failRemove = true }
        val logged = mutableListOf<String>()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage, log = { logged += it })
        controller.findOldFiles()
        val before = storage.measured
        controller.removeOldFiles()
        assertEquals(OldFilesState.RemoveFailed, controller.oldFiles.value)
        assertFalse(controller.clearing.value)
        assertEquals(before + 1, storage.measured)
        assertEquals(listOf("settings: remove old files failed: IllegalStateException"), logged)
    }

    @Test
    fun aSecondTapWhileRemovingDoesNothing() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage()
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        storage.gate = CompletableDeferred()
        controller.removeOldFiles()
        assertIs<OldFilesState.Removing>(controller.oldFiles.value)
        assertTrue(controller.clearing.value)
        controller.removeOldFiles()
        assertEquals(1, storage.removed.size)
        storage.gate!!.complete(Unit)
        assertIs<OldFilesState.Removed>(controller.oldFiles.value)
        assertEquals(1, storage.removed.size)
    }

    @Test
    fun aPartialRemovalKeepsWhatStayed() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val partial = OldFilesResult(removed = 1, bytes = 5, kept = 1, failed = 1)
        val storage = FakeStorage().apply { removeResult = partial }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        controller.findOldFiles()
        controller.removeOldFiles()
        val done = assertIs<OldFilesState.Removed>(controller.oldFiles.value)
        assertEquals(partial, done.result)
        assertEquals(1, done.result.kept)
        assertEquals(1, done.result.failed)
        assertEquals(listOf(RemovedLine.Removed, RemovedLine.NotRemoved, RemovedLine.Kept), removedLines(done.result))
        // Nothing removed is its own line, a rebuild that failed adds its own.
        assertEquals(listOf(RemovedLine.NoneRemoved, RemovedLine.Kept), removedLines(OldFilesResult(0, 0, 3)))
        assertEquals(listOf(RemovedLine.Removed, RemovedLine.HistoryLater), removedLines(OldFilesResult(2, 9, 0, rebuildFailed = true)))
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

    @Test
    fun thePickerOffersTheArchiveDaysPlusOneOnEachSide() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val storage = FakeStorage().apply { archive = OldFilesRange(20_000, 20_010) }
        val controller = SettingsController(scope, SettingsRepository(MemoryStore()), storage)
        assertNull(controller.archiveDays.value)
        controller.refreshStorage()
        assertEquals(OldFilesRange(20_000, 20_010), controller.archiveDays.value)
        assertEquals(19_999L..20_011L, pickableDays(controller.archiveDays.value))
        assertNull(pickableDays(null))
        assertEquals(2024, yearOf(19_723))
        assertEquals(2023, yearOf(19_722))
        assertEquals(1969, yearOf(-1))
    }
}
