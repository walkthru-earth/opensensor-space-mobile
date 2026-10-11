package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.FakeArchiveDevice
import earth.walkthru.aqsync.data.sync.MemoryArchiveIndex
import earth.walkthru.aqsync.data.sync.MemoryArchiveStore
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkState
import earth.walkthru.aqsync.storage.FakeStorageBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The collection state machine over a fake link provider and a counting keep alive (#56). */
class CollectionControllerTest {
    private val store = MemoryArchiveStore()
    private val index = MemoryArchiveIndex()
    private val logs = ArrayList<String>()
    private var nextId = 1L

    private fun TestScope.collector() = ArchiveCollector(
        store,
        index,
        SyncEngine(store, index, windowRetryBackoffMs = 5, clock = { 1_000L }, io = StandardTestDispatcher(testScheduler)),
        FakeStorageBudget(),
        testTimeSource,
    )

    private fun TestScope.controller(
        links: FakeLinks,
        keepAlive: CountingKeepAlive,
        device: String = "fake-0",
        retry: RetryPolicy = RetryPolicy(),
        permits: Semaphore? = null,
        scope: CoroutineScope = backgroundScope,
        onLog: (String) -> Unit = {},
    ): CollectionController = CollectionController(
        device,
        links,
        collector(),
        keepAlive,
        scope,
        retry,
        permits,
        time = testTimeSource,
        clock = { testScheduler.currentTime },
        log = {
            logs += it
            onLog(it)
        },
    ).also { c -> keepAlive.observe = { c.state.value } }

    private fun intent(scope: CollectionScope = CollectionScope.All, device: String = "fake-0") =
        CollectionIntent(nextId++, device, scope, CollectionOrigin.USER_TAP)

    private fun archived(links: FakeLinks, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(links.station, name))]

    private fun assertAllArchived(links: FakeLinks) =
        links.files.forEach { assertContentEquals(it.bytes, archived(links, it.name), it.name) }

    /**
     * Wait for [controller] to end, then run what is due now so its finally has run.
     * `advanceUntilIdle` would not, it ignores the background scope the job runs in.
     */
    private suspend fun TestScope.settle(controller: CollectionController) {
        controller.state.first { !it.active }
        runCurrent()
    }

    @Test
    fun startTakesProcessBeforeItsFirstSuspension() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, keepAlive)

        val result = controller.start(intent())

        assertIs<StartResult.Started>(result)
        assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
        assertEquals(CollectionState.Preparing(controller.state.value.let { (it as CollectionState.Preparing).intent }, PrepareStep.CONNECT, 1), controller.state.value)
        assertEquals(0, links.connects, "nothing ran yet")
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aSecondStartWhileRunningIsBusyAndTakesNoLease() = runTest {
        val keepAlive = CountingKeepAlive()
        val controller = controller(FakeLinks(backgroundScope), keepAlive)
        val first = intent()

        controller.start(first)

        assertEquals(StartResult.Busy(first.id), controller.start(intent()))
        assertEquals(1, keepAlive.acquired(KeepAliveKind.PROCESS))
        settle(controller)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aRefusedProcessLeaseFailsWithoutConnecting() = runTest {
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, CountingKeepAlive(refuse = setOf(KeepAliveKind.PROCESS)))
        val request = intent()

        assertEquals(StartResult.Refused(KeepAliveKind.PROCESS), controller.start(request))

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS)), controller.state.value)
        settle(controller)
        assertEquals(0, links.connects)
        assertEquals(controller.state.value, controller.events.tryReceive().getOrNull())
    }

    @Test
    fun doneClosesEveryLeaseOnceAndArchivesEveryFile() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        val done = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(request.id, done.intentId)
        assertEquals(3, done.summary.files)
        assertTrue(done.summary.complete)
        assertAllArchived(links)
        assertEquals(1, keepAlive.acquired(KeepAliveKind.PROCESS))
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(1, links.releases)
        assertEquals(done, controller.events.tryReceive().getOrNull())
    }

    @Test
    fun aSlowListKeepsProcessHeldThroughout() = runTest {
        // A1. LIST stays silent for its first 60 s. The 15 s first frame deadline ends attempts in
        // that window, and the request waits and lists again with PROCESS held every second.
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { if (testScheduler.currentTime < 60_000) listDelayMs = 60_000 }
        val controller = controller(links, keepAlive, retry = RetryPolicy(attempts = 10))

        controller.start(intent())
        repeat(60) {
            advanceTimeBy(1_000)
            assertTrue(controller.state.value.active, "active at ${testScheduler.currentTime} ms")
            assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS), "PROCESS at ${testScheduler.currentTime} ms")
        }
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertAllArchived(links)
        assertEquals(1, keepAlive.acquired(KeepAliveKind.PROCESS))
        assertTrue(keepAlive.allClosedOnce())
        assertTrue(logs.any { it.contains("waiting for attempt 2") }, logs.toString())
    }

    @Test
    fun aLossDuringListWaitsThenListsAgainAndArchives() = runTest {
        // A2.
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { n -> if (n == 1) beforeList = { close() } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertAllArchived(links)
        val order = listOf(
            "collection ${request.id} preparing list attempt 1",
            "collection ${request.id} waiting for attempt 2, ",
            "collection ${request.id} preparing list attempt 2",
            "collection ${request.id} preparing plan attempt 2",
            "collection ${request.id} finalizing summary",
        ).map { prefix -> logs.indexOfFirst { it.startsWith(prefix) } }
        assertTrue(order.all { it >= 0 } && order.zipWithNext().all { (a, b) -> a < b }, logs.toString())
        assertEquals(2, links.connects)
        assertTrue(Opcode.LIST in links.devices[1].requests, "the retry listed afresh")
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(2, links.releases)
    }

    @Test
    fun aLossDuringTransferResumesFromAFreshListByOffset() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { n -> if (n == 1) beforeRead = { k -> if (k == 2) close() } }
        val controller = controller(links, keepAlive)

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertAllArchived(links)
        val retry = links.devices[1]
        assertTrue(Opcode.LIST in retry.requests, "the retry listed afresh")
        assertTrue(retry.readOffsets.first() > 0, "resumed at ${retry.readOffsets.first()}")
        assertTrue(logs.any { it.contains("waiting for attempt 2") }, logs.toString())
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aRejectedCredentialFailsWithoutRetry() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { DisconnectCause.Auth(AuthKind.TOKEN_REJECTED) } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED))), controller.state.value)
        assertEquals(1, links.connects)
        assertFalse(logs.any { it.contains("waiting") }, logs.toString())
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aDeliberateCloseStopsWithoutRetry() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { DisconnectCause.Deliberate("user disconnect") } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.APP), controller.state.value)
        assertEquals(1, links.connects)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun retriesExhaustIntoFailedWithTheLastCause() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply {
            refuse = { n -> if (n < 3) DisconnectCause.PeerReset else DisconnectCause.Unreachable }
        }
        val controller = controller(links, keepAlive, retry = RetryPolicy(attempts = 4))
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Unreachable)), controller.state.value)
        assertEquals(4, links.connects)
        // 2 s, 4 s and 8 s between the four attempts.
        assertEquals(14_000, testScheduler.currentTime)
        assertEquals(1, keepAlive.acquired(KeepAliveKind.PROCESS))
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun processStaysHeldWhileWaiting() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { n -> if (n == 1) DisconnectCause.PeerReset else null } }
        val controller = controller(links, keepAlive)

        controller.start(intent())
        advanceTimeBy(1_000)

        val waiting = assertIs<CollectionState.Waiting>(controller.state.value)
        assertEquals(2, waiting.attempt)
        assertEquals(2_000, waiting.retryAtEpochMs)
        assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun stopDuringListEndsStoppedAndLeavesTheLinkReady() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, keepOpen = true) { n -> if (n == 1) listDelayMs = 5_000 }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        advanceTimeBy(1_000)
        assertEquals(PrepareStep.LIST, assertIs<CollectionState.Preparing>(controller.state.value).step)

        assertTrue(controller.stop(StopSource.USER))

        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        settle(controller)
        // Let the abandoned LIST drain behind its ABORT.
        advanceTimeBy(10_000)
        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(1, links.releases)
        val link = links.links.single().session
        assertIs<LinkState.Ready>(link.link.state.value)
        assertTrue(Opcode.ABORT in links.devices.single().requests, "the abandoned LIST was aborted")
        // No backlog. The next request on the same link gets its own reply at once.
        links.devices.single().listDelayMs = 0
        val (files, _) = DeviceSession(link.link, link.transport).list()
        assertEquals(links.files.size, files.size)
        assertFalse(controller.stop(StopSource.USER), "nothing left to stop")
        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.events.tryReceive().getOrNull())
    }

    @Test
    fun stopDuringTransferClosesEveryLeaseOnce() = runTest {
        val keepAlive = CountingKeepAlive()
        lateinit var controller: CollectionController
        val links = FakeLinks(backgroundScope, kind = TransportKind.LAN) { n ->
            if (n == 1) beforeRead = { k -> if (k == 2) controller.stop(StopSource.SYSTEM) }
        }
        controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.SYSTEM), controller.state.value)
        assertEquals(1, links.connects)
        assertEquals(1, keepAlive.acquired(KeepAliveKind.WIFI))
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aStopAfterTheOutcomeChangesNothing() = runTest {
        val keepAlive = CountingKeepAlive()
        lateinit var controller: CollectionController
        var lateStop: Boolean? = null
        val links = FakeLinks(backgroundScope)
        val controllerLinks = LinkProvider { device, prefer ->
            val link = links.connect(device, prefer)
            // Release runs in the job's finally, after Done is published and before PROCESS goes.
            CollectionLink(link.session, link.station, link.maxRead) {
                lateStop = controller.stop(StopSource.USER)
                link.release()
            }
        }
        controller = CollectionController(
            "fake-0",
            controllerLinks,
            collector(),
            keepAlive,
            backgroundScope,
            time = testTimeSource,
            clock = { testScheduler.currentTime },
            log = { logs += it },
        )
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(false, lateStop)
        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun stopRacingCompletionWinsAndClosesOnce() = runTest {
        // Stop lands after the last file and the summary, before the job publishes Done.
        val keepAlive = CountingKeepAlive()
        lateinit var controller: CollectionController
        controller = controller(FakeLinks(backgroundScope, kind = TransportKind.LAN), keepAlive) { line ->
            if (line.endsWith("finalizing summary")) controller.stop(StopSource.USER)
        }
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.events.tryReceive().getOrNull())
        assertTrue(controller.events.tryReceive().isFailure, "no Done after the Stop")
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(2, keepAlive.leases.size)
    }

    @Test
    fun theSameIntentStartedAgainAfterStopIsNotOverwrittenByTheOldJob() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { n -> if (n == 1) listDelayMs = 5_000 }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        advanceTimeBy(1_000)
        assertTrue(controller.stop(StopSource.USER))
        assertIs<StartResult.Started>(controller.start(request))
        settle(controller)

        val done = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(3, done.summary.files)
        assertAllArchived(links)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aRestartAfterStopWaitsForTheOldLendOnAKeptLink() = runTest { restartOnASharedLink(keepOpen = true) }

    @Test
    fun aRestartAfterStopWaitsForTheOldLendOnAClosingLink() = runTest { restartOnASharedLink(keepOpen = false) }

    /**
     * One link per device. The stopped run is still in a handshake it cannot leave, so it holds
     * its lend after Stop. The next run must not borrow the link until that run handed it back.
     */
    private suspend fun TestScope.restartOnASharedLink(keepOpen: Boolean) {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, keepOpen = keepOpen, share = true).apply {
            connectDelayMs = { n -> if (n == 1) 5_000 else 0 }
            uninterruptible = true
        }
        val controller = controller(links, keepAlive)

        controller.start(intent())
        advanceTimeBy(1_000)
        assertTrue(controller.stop(StopSource.USER))
        val second = intent()
        assertIs<StartResult.Started>(controller.start(second))
        settle(controller)

        val done = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(second.id, done.intentId)
        assertAllArchived(links)
        assertEquals(1, links.maxLent, "one lender at a time")
        assertEquals(0, links.lent)
        assertEquals(if (keepOpen) 1 else 2, links.devices.size)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aRunStoppedWhileWaitingForTheOldLendStillKeepsTheOrder() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, share = true).apply {
            connectDelayMs = { n -> if (n == 1) 5_000 else 0 }
            uninterruptible = true
        }
        val controller = controller(links, keepAlive)

        controller.start(intent())
        advanceTimeBy(1_000)
        assertTrue(controller.stop(StopSource.USER))
        controller.start(intent())
        runCurrent()
        assertTrue(controller.stop(StopSource.USER))
        val third = intent()
        controller.start(third)
        settle(controller)

        assertEquals(third.id, assertIs<CollectionState.Done>(controller.state.value).intentId)
        assertEquals(1, links.maxLent, "one lender at a time")
        assertEquals(2, links.connects, "the run stopped while waiting never connected")
        assertEquals(0, links.lent)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun stopDuringConnectLeavesNoLendOutstanding() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, keepOpen = true).apply { connectDelayMs = { 5_000 } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        advanceTimeBy(1_000)
        assertEquals(1, links.lent)
        assertTrue(controller.stop(StopSource.USER))
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        assertEquals(1, links.connects)
        assertEquals(1, links.releases)
        assertEquals(0, links.lent)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aThrowingReleaseStillFreesThePermitAndProcess() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { failRelease = true }
        val permits = Semaphore(1)
        val controller = controller(links, keepAlive, permits = permits)

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, permits.availablePermits)
        assertTrue(keepAlive.allClosedOnce())
        assertTrue(logs.any { it.endsWith("cleanup link failed, IllegalStateException") }, logs.toString())
        links.failRelease = false
        assertIs<StartResult.Started>(controller.start(intent()))
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, permits.availablePermits)
    }

    @Test
    fun aCredentialRejectedMidTransferFailsWithoutRetry() = runTest {
        val keepAlive = CountingKeepAlive()
        var opens = 0
        val links = FakeLinks(backgroundScope) {
            refuseOpen = { if (++opens == 2) Frame.Error(0, ProtocolError.AUTH_FAILED.code, "") else null }
        }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED))), controller.state.value)
        assertEquals(1, links.connects)
        assertFalse(logs.any { it.contains("waiting") }, logs.toString())
        assertEquals(1, links.releases)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aDeliberateCloseMidTransferStopsWithoutRetry() = runTest {
        val keepAlive = CountingKeepAlive()
        lateinit var links: FakeLinks
        links = FakeLinks(backgroundScope, keepOpen = true) { n ->
            if (n == 1) beforeRead = { k -> if (k == 2) links.links.last().session.link.close("user disconnect") }
        }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.APP), controller.state.value)
        assertEquals(1, links.connects)
        assertFalse(logs.any { it.contains("waiting") }, logs.toString())
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun progressEveryAttemptRetriesUntilTheCeiling() = runTest {
        val keepAlive = CountingKeepAlive()
        // Each attempt lists for 10 s, gets a chunk or two of a large file, then loses the link.
        val links = FakeLinks(backgroundScope) {
            listDelayMs = 10_000
            chunkDelayMs = 1_000
            beforeRead = { k ->
                if (k == 1) {
                    backgroundScope.launch {
                        delay(1_500)
                        close()
                    }
                }
            }
        }
        links.files.clear()
        links.files += FakeArchiveDevice.FakeFile(
            "station=${links.station}/year=2026/month=09/day=15/data_1000_b41d_15000-15089-90.parquet",
            FakeArchiveDevice.fakeParquet(7, 4_000_000),
        )
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        val failed = assertIs<CollectionState.Failed>(controller.state.value)
        assertEquals(request.id, failed.intentId)
        assertIs<CollectionFailure.Link>(failed.failure)
        // Progress resets the attempt bound, so only the 30 min ceiling ends it.
        assertTrue(links.connects > RetryPolicy().attempts, "${links.connects} connects")
        // No retry starts past 30 min. The attempt already running ends within its own 15 s.
        assertTrue(testScheduler.currentTime in 29 * 60_000L..30 * 60_000L + 15_000L, "${testScheduler.currentTime} ms")
        assertEquals(1, keepAlive.acquired(KeepAliveKind.PROCESS))
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun stopWhileWaitingEndsStoppedWithoutAnotherConnect() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { DisconnectCause.PeerReset } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        advanceTimeBy(1_000)
        assertIs<CollectionState.Waiting>(controller.state.value)
        assertTrue(controller.stop(StopSource.USER))
        settle(controller)
        advanceTimeBy(60_000)

        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        assertEquals(1, links.connects)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aCancelledScopeStillEndsTheRequest() = runTest {
        val keepAlive = CountingKeepAlive()
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val controller = controller(FakeLinks(backgroundScope) { listDelayMs = 5_000 }, keepAlive, scope = owner)
        val request = intent()

        controller.start(request)
        advanceTimeBy(1_000)
        owner.cancel()
        runCurrent()

        assertEquals(CollectionState.Stopped(request.id, StopSource.SYSTEM), controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aStartOnAnEndedScopeStillLetsGoAndAllowsTheNext() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        owner.cancel()
        val controller = controller(links, keepAlive, scope = owner)
        val request = intent()

        assertIs<StartResult.Started>(controller.start(request))
        runCurrent()

        assertEquals(CollectionState.Stopped(request.id, StopSource.SYSTEM), controller.state.value)
        assertEquals(0, links.connects)
        assertTrue(keepAlive.allClosedOnce())
        assertIs<StartResult.Started>(controller.start(intent()))
        runCurrent()
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aSharedPermitQueuesTheSecondDeviceAndIsReleasedOnce() = runTest {
        val permits = Semaphore(1)
        val keepAliveA = CountingKeepAlive()
        val keepAliveB = CountingKeepAlive()
        val keepAliveC = CountingKeepAlive()
        val linksA = FakeLinks(backgroundScope) { chunkDelayMs = 5 }
        val linksB = FakeLinks(backgroundScope, station = "00000000-0000-4000-8000-000000000002")
        val linksC = FakeLinks(backgroundScope, station = "00000000-0000-4000-8000-000000000003")
        val a = controller(linksA, keepAliveA, device = "fake-a", permits = permits)
        val b = controller(linksB, keepAliveB, device = "fake-b", permits = permits)
        val c = controller(linksC, keepAliveC, device = "fake-c", permits = permits)
        val requestC = intent(device = "fake-c")

        a.start(intent(device = "fake-a"))
        b.start(intent(device = "fake-b"))
        c.start(requestC)
        advanceTimeBy(100)
        assertEquals(1, linksA.connects)
        assertEquals(0, linksB.connects, "B waits for the permit")
        assertEquals(1, keepAliveB.held(KeepAliveKind.PROCESS), "and holds PROCESS while it waits")
        assertTrue(c.stop(StopSource.USER), "C is stopped while queued")
        settle(a)
        settle(b)
        settle(c)

        assertIs<CollectionState.Done>(a.state.value)
        assertIs<CollectionState.Done>(b.state.value)
        assertEquals(CollectionState.Stopped(requestC.id, StopSource.USER), c.state.value)
        assertEquals(0, linksC.connects)
        assertEquals(1, permits.availablePermits)
        assertTrue(keepAliveA.allClosedOnce() && keepAliveB.allClosedOnce() && keepAliveC.allClosedOnce())
    }

    @Test
    fun stopRightAfterStartStillClosesProcessOnce() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        assertTrue(controller.stop(StopSource.USER, request.id))
        settle(controller)

        assertEquals(CollectionState.Stopped(request.id, StopSource.USER), controller.state.value)
        assertEquals(0, links.connects)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun wifiIsHeldOnlyWhileALanLinkCarriesData() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, kind = TransportKind.LAN) { n -> if (n == 1) beforeRead = { k -> if (k == 2) close() } }
        val controller = controller(links, keepAlive)

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        val wifi = keepAlive.log.filter { "WIFI" in it }
        // Taken at LIST on each link, let go while waiting, let go again after the summary.
        assertEquals(
            listOf("acquire WIFI in Preparing", "close WIFI in Waiting", "acquire WIFI in Preparing", "close WIFI in Done"),
            wifi,
        )
        assertEquals(2, keepAlive.acquired(KeepAliveKind.WIFI))
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aBluetoothLinkNeverTakesWifi() = runTest {
        val keepAlive = CountingKeepAlive()
        val controller = controller(FakeLinks(backgroundScope, kind = TransportKind.BLE), keepAlive)

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(0, keepAlive.acquired(KeepAliveKind.WIFI))
    }

    @Test
    fun aRefusedWifiLeaseDoesNotStopTheCollection() = runTest {
        val keepAlive = CountingKeepAlive(refuse = setOf(KeepAliveKind.WIFI))
        val controller = controller(FakeLinks(backgroundScope, kind = TransportKind.LAN), keepAlive)

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, logs.count { it.endsWith("wifi lease refused") }, logs.toString())
        assertEquals(1, keepAlive.log.count { it.startsWith("acquire WIFI") }, "asked once while the link carried data")
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun twoDevicesRunIndependentMachines() = runTest {
        val keepAliveA = CountingKeepAlive()
        val keepAliveB = CountingKeepAlive()
        val linksA = FakeLinks(backgroundScope) { chunkDelayMs = 5 }
        val linksB = FakeLinks(backgroundScope, station = "00000000-0000-4000-8000-000000000002") { chunkDelayMs = 5 }
        val a = controller(linksA, keepAliveA, device = "fake-a")
        val b = controller(linksB, keepAliveB, device = "fake-b")
        val requestA = intent(device = "fake-a")
        val requestB = intent(device = "fake-b")

        a.start(requestA)
        b.start(requestB)
        advanceTimeBy(100)
        assertTrue(a.state.value.active && b.state.value.active)
        assertTrue(a.stop(StopSource.USER))
        settle(b)
        settle(a)

        assertEquals(CollectionState.Stopped(requestA.id, StopSource.USER), a.state.value)
        assertIs<CollectionState.Done>(b.state.value)
        assertAllArchived(linksB)
        assertTrue(keepAliveA.allClosedOnce() && keepAliveB.allClosedOnce())
    }

    @Test
    fun syncOneAndSyncAllBothPlanFromAFreshList() = runTest {
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, CountingKeepAlive())

        controller.start(intent())
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        // A file the firmware wrote after the first run. Only a new LIST can know it.
        val added = FakeArchiveDevice.FakeFile(
            "station=${links.station}/year=2026/month=09/day=15/data_1030_b41d_15180-15269-90.parquet",
            FakeArchiveDevice.fakeParquet(4, 12_000),
        )
        links.files += added

        controller.start(intent(CollectionScope.One(added.name)))
        settle(controller)

        val one = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, one.summary.files)
        assertContentEquals(added.bytes, archived(links, added.name))
        assertEquals(1, links.devices[1].requests.count { it == Opcode.LIST })
        assertEquals(listOf(0L), links.devices[1].readOffsets.distinct().take(1))

        links.files += FakeArchiveDevice.FakeFile(
            "station=${links.station}/year=2026/month=09/day=15/data_1045_b41d_15270-15359-90.parquet",
            FakeArchiveDevice.fakeParquet(5, 11_000),
        )
        controller.start(intent())
        settle(controller)

        val all = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(5, all.summary.files)
        assertEquals(1, links.devices[2].requests.count { it == Opcode.LIST })
        assertAllArchived(links)
    }

    @Test
    fun aFileNoLongerListedIsReportedNotTransferred() = runTest {
        val links = FakeLinks(backgroundScope)
        val controller = controller(links, CountingKeepAlive())

        controller.start(intent(CollectionScope.One("station=${links.station}/year=2026/month=09/day=15/gone.parquet")))
        settle(controller)

        val done = assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, done.summary.notSaved.size)
        assertFalse(Opcode.OPEN in links.devices.single().requests)
    }

    @Test
    fun retryWaitsFollowThePolicy() {
        val policy = RetryPolicy(attempts = 5, firstBackoff = 2.seconds, maxBackoff = 5.seconds, ceiling = 20.seconds)
        assertEquals(2.seconds, policy.backoff(DisconnectCause.PeerReset, 2, 0.seconds))
        assertEquals(4.seconds, policy.backoff(DisconnectCause.PeerReset, 3, 0.seconds))
        assertEquals(5.seconds, policy.backoff(DisconnectCause.PeerReset, 4, 0.seconds))
        assertEquals(null, policy.backoff(DisconnectCause.PeerReset, 6, 0.seconds))
        assertEquals(null, policy.backoff(DisconnectCause.PeerReset, 2, 19.seconds))
        assertEquals(null, policy.backoff(DisconnectCause.Auth(AuthKind.BOND_FAILED), 2, 0.seconds))
    }

    @Test
    fun aRetryPolicyRejectsBoundsThatCannotWait() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(attempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(firstBackoff = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(firstBackoff = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(firstBackoff = 10.seconds, maxBackoff = 5.seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(ceiling = 0.seconds) }
    }
}
