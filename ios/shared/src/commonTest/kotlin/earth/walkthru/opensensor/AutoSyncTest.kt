package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.background.BackgroundSyncPolicy
import earth.walkthru.opensensor.permissions.AccessStatus
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS Auto-sync switch store and the background run bridge over fake Swift ports. */
class AutoSyncTest {
    private val a = "11111111-1111-1111-1111-111111111111"
    private val b = "22222222-2222-2222-2222-222222222222"

    private class Memory(var text: String? = null) :
        AutoSyncStorage,
        KnownRecordStorage {
        override fun load(): String? = text
        override fun save(text: String) {
            this.text = text
        }
    }

    private var clock = 1_000_000L
    private var changes = 0

    private fun settings(storage: AutoSyncStorage = Memory(), status: Long = 0) =
        AutoSyncSettings(storage, { clock }, { status }, { it() }, { changes++ }, continuedTask = true)

    private fun devices() = DevicesBridge(CompanionState(), emptyFlow(), Memory(), { clock }, {}, {}, { _, _ -> }, { _, _ -> }).also {
        it.sensorOpened(a, "Kitchen", "st-a", "aabbccddeeff")
        it.sensorOpened(b, "Garden", "st-b", "112233445566")
    }

    @Test
    fun storeRoundTripsAndDropsDamagedLines() {
        val entries = listOf(AutoSyncEntry(a, null), AutoSyncEntry(b, 42))
        assertEquals(entries, IosAutoSync.decode(IosAutoSync.encode(entries)))
        assertEquals(listOf(AutoSyncEntry(a, 7)), IosAutoSync.decode("${a.lowercase()}\t7\nnot-an-id\t1\n$a\t9\nbroken"))
        assertTrue(IosAutoSync.decode(null).isEmpty())
    }

    @Test
    fun switchKeepsTheLastRunAndForgets() {
        val storage = Memory()
        val s = settings(storage)
        assertFalse(s.isOn(a))
        s.set(a.lowercase(), true)
        assertTrue(s.isOn(a))
        assertEquals(1, changes)
        s.markRun(setOf(a))
        s.set(a, true)
        assertEquals(clock, IosAutoSync.decode(storage.text).single().lastRunMs)
        assertFalse(s.isDue(a))
        clock += BackgroundSyncPolicy.PERIOD_MS
        assertTrue(s.isDue(a))
        s.forget(a)
        assertFalse(s.isOn(a))
        assertEquals(emptyList(), settings(storage).enabledPeripherals())
    }

    @Test
    fun onlyANeverAskedStatusPrompts() {
        assertTrue(AutoSyncAsk.shouldAsk(AccessStatus.NOT_DETERMINED))
        assertFalse(AutoSyncAsk.shouldAsk(AccessStatus.GRANTED))
        assertFalse(AutoSyncAsk.shouldAsk(AccessStatus.DENIED))
        assertFalse(AutoSyncAsk.shouldAsk(AccessStatus.RESTRICTED))
        assertEquals(AccessStatus.GRANTED, settings(status = 2).notificationAccess())
    }

    @Test
    fun bytesReadLikeFiles() {
        assertEquals("512 B", IosAutoSync.bytes(512))
        assertEquals("1.5 KiB", IosAutoSync.bytes(1536))
        assertEquals("2.00 MiB", IosAutoSync.bytes(2L * 1024 * 1024))
    }

    @Test
    fun dueRunTriesOnlyEnabledDueSensorsAndRecordsThem() = runTest {
        val s = settings()
        s.set(a, true)
        s.set(b, true)
        s.markRun(setOf(b))
        val synced = mutableListOf<Pair<String, String?>>()
        var browsed = 0
        val bridge = BackgroundSyncBridge(
            devices = devices(),
            settings = s,
            scope = this,
            browse = { _, done ->
                browsed++
                done(emptyList(), emptyList())
            },
            sync = { id, wifi, done ->
                synced += id to wifi
                done(BackgroundStepResult(0, 0, 0, null, false))
            },
            isOwned = { false },
            log = {},
        )
        var summary: BackgroundRunSummary? = null
        val handle = bridge.runDue(null) { summary = it }
        assertEquals(1, handle.total)
        testScheduler.advanceUntilIdle()
        // b ran this period. a has no Wi-Fi credential, so no browse, straight to its bond.
        assertEquals(listOf<Pair<String, String?>>(a to null), synced)
        assertEquals(0, browsed)
        val done = assertNotNull(summary)
        assertFalse(done.notify)
        assertFalse(done.retry)
        assertFalse(s.isDue(a))
    }

    @Test
    fun ownedOrSkippedSensorsStayDue() = runTest {
        val s = settings()
        s.set(a, true)
        s.set(b, true)
        val bridge = BackgroundSyncBridge(
            devices = devices(),
            settings = s,
            scope = this,
            browse = { _, done -> done(emptyList(), emptyList()) },
            sync = { id, _, done -> done(BackgroundStepResult(0, 0, 0, "connect failed", false)) },
            isOwned = { it == a },
            log = {},
        )
        var summary: BackgroundRunSummary? = null
        bridge.runDue(null) { summary = it }
        testScheduler.advanceUntilIdle()
        assertNotNull(summary)
        assertTrue(s.isDue(a))
        assertTrue(s.isDue(b))
        // A second request while idle again runs, a cancelled one never reports.
        summary = null
        bridge.runOne(b) { summary = it }.cancel()
        testScheduler.advanceUntilIdle()
        assertNull(summary)
    }

    @Test
    fun stepResultsFollowTheSyncEnd() {
        val done = BackgroundSteps.ended(SyncResult(SyncEnd.COMPLETE, 4, 3, 0), 2048)
        assertEquals(3, done.newFiles)
        assertEquals(2048, done.newBytes)
        assertNull(done.skipped)
        assertFalse(done.incomplete)
        // Lost links keep what they saved and ask for a retry.
        val lost = BackgroundSteps.ended(SyncResult(SyncEnd.LINK_LOST, 4, 1, 0), 10)
        assertEquals(1, lost.newFiles)
        assertTrue(lost.incomplete)
        assertTrue(BackgroundSteps.lost().incomplete)
        // Nothing was sent, so the next link is tried and the sensor stays due.
        assertEquals("in use", BackgroundSteps.ended(SyncResult(SyncEnd.IN_USE, 0, 0, 0), 0).skipped)
        assertNotNull(BackgroundSteps.ended(SyncResult(SyncEnd.AUTH_FAILED, 0, 0, 0), 0).skipped)
        // A fault outside one file still shows in the summary.
        assertEquals(1, BackgroundSteps.ended(SyncResult(SyncEnd.FAILED, 2, 0, 0), 0).failures)
        assertEquals(2, BackgroundSteps.ended(SyncResult(SyncEnd.FILES_NOT_SAVED, 5, 3, 2), 0).failures)
    }

    @Test
    fun deviceForFindsTheHardwareId() {
        val d = devices()
        assertEquals("aabbccddeeff", d.deviceFor(a.lowercase()))
        assertNull(d.deviceFor("33333333-3333-3333-3333-333333333333"))
    }
}
