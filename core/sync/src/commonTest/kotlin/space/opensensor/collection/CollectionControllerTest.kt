package space.opensensor.collection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.sync.ARCHIVE_HEADROOM_BYTES
import space.opensensor.data.sync.ArchiveCollector
import space.opensensor.data.sync.FakeArchiveDevice
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.MemoryArchiveIndex
import space.opensensor.data.sync.MemoryArchiveStore
import space.opensensor.data.sync.SyncEngine
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkState
import space.opensensor.link.TimeoutPhase
import space.opensensor.link.UnavailableKind
import space.opensensor.storage.FakeStorageBudget
import space.opensensor.storage.StorageClass
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

    private fun archived(links: FakeLinks, name: String): ByteArray? = store.files[ArchiveLayout.finalPath(ArchiveKey(links.thing, name))]

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
    fun platformCleanupKeepsProcessAndPermitUntilItFinishesEvenWhenItThrows() = runTest {
        val cleaning = CompletableDeferred<Unit>()
        val permits = Semaphore(1)
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val request = intent()
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            permits = permits,
            onCleanup = { stopped ->
                assertEquals(request, stopped)
                assertEquals(1, links.releases, "the platform cleanup follows the returned lend")
                cleaning.complete(Unit)
                delay(100)
                error("platform close failed")
            },
        )

        controller.start(request)
        cleaning.await()
        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
        assertEquals(0, permits.availablePermits)
        val cleanup = async { controller.awaitCleanup(request.id) }
        runCurrent()
        assertFalse(cleanup.isCompleted, "cleanup completion includes the platform close")
        advanceTimeBy(100)
        runCurrent()
        cleanup.await()

        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(1, permits.availablePermits, "a failed platform close cannot retain the permit")
    }

    @Test
    fun timeoutDuringCloseRetainsTheCommittedFileAndStopsWithoutWaitingForClosed() = runTest {
        val saved = CompletableDeferred<Unit>()
        val outcomes = ArrayList<FileOutcome>()
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, keepOpen = true) { closedDelayMs = 5_000 }
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onOutcome = { _, outcome ->
                outcomes += outcome
                saved.complete(Unit)
            },
        )
        val request = intent()

        controller.start(request)
        saved.await()
        runCurrent()
        assertTrue(Opcode.CLOSE in links.devices.single().requests, "CLOSE is queued and waiting for delayed CLOSED")
        assertEquals(1, index.records.size)
        val stoppedAt = testScheduler.currentTime
        assertTrue(controller.stop(StopSource.FGS_TIMEOUT, request.id))
        controller.awaitCleanup(request.id)

        assertEquals(stoppedAt, testScheduler.currentTime, "Stop must not wait for CLOSED")
        assertEquals(CollectionState.Stopped(request.id, StopSource.FGS_TIMEOUT), controller.state.value)
        val synced = assertIs<FileOutcome.Synced>(outcomes.single())
        assertFalse(synced.alreadyArchived)
        assertEquals(1, outcomes.filterIsInstance<FileOutcome.Synced>().count { !it.alreadyArchived })
        assertEquals(links.files.first().bytes.size.toLong(), synced.bytes)
        assertContentEquals(links.files.first().bytes, archived(links, synced.name))
        assertTrue(keepAlive.allClosedOnce())
        advanceTimeBy(5_001)
        runCurrent()
        assertIs<LinkState.Ready>(links.links.single().session.link.state.value)
        assertEquals(1, outcomes.size, "the late CLOSED cannot re-report the saved file")
    }

    @Test
    fun repairingAReceiptOnRetryCannotEraseAnEarlierNewFileOutcome() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { n ->
            if (n == 1) beforeOpen = { name -> if (name == files[1].name) close() }
        }
        val observed = ArrayList<FileOutcome>()
        var receiptRemoved = false
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onOutcome = { _, outcome ->
                observed += outcome
                if (!receiptRemoved && outcome is FileOutcome.Synced) {
                    receiptRemoved = true
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { index.remove(ArchiveKey(links.thing, outcome.name)) }
                }
            },
        )

        controller.start(intent())
        settle(controller)

        val summary = assertIs<CollectionState.Done>(controller.state.value).summary
        assertEquals(2, links.connects)
        assertTrue(observed.filterIsInstance<FileOutcome.Synced>().any { it.alreadyArchived }, "the repaired receipt reuses immutable bytes")
        assertEquals(3, summary.newFiles)
        assertEquals(links.files.sumOf { it.bytes.size.toLong() }, summary.newBytes)
        assertEquals(3, summary.outcomes.filterIsInstance<FileOutcome.Synced>().count { !it.alreadyArchived })
        assertAllArchived(links)
    }

    @Test
    fun acceptedClockEvidenceIsAwaitedOnTheExactLinkBeforeListing() = runTest {
        val accepted = Frame.TimeSet(1_789_581_931L, 123_456L, 789_000)
        var persisted = false
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) {
            clockAck = accepted
            beforeList = { assertTrue(persisted, "LIST must follow receipt persistence") }
        }
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            clock = { 1_789_581_931_789L },
            onClockSet = { link, ack ->
                assertTrue(link === links.links.single(), "the receipt belongs to the exact borrowed link")
                assertEquals(accepted, ack)
                assertFalse(Opcode.LIST in links.devices.single().requests)
                assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
                delay(50)
                persisted = true
            },
        )

        controller.start(intent().copy(clockConfirmed = true))
        settle(controller)

        assertTrue(persisted)
        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(1, links.devices.single().requests.count { it == Opcode.SET_TIME })
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun timeoutAfterClockAcknowledgementPersistsEvidenceThenStopsBeforeListing() = runTest {
        val accepted = Frame.TimeSet(1_789_581_931L, 123_456L, 789_000)
        val recording = CompletableDeferred<Unit>()
        var recorded: Frame.TimeSet? = null
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) { clockAck = accepted }
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            clock = { 1_789_581_931_789L },
            onClockSet = { _, ack ->
                recording.complete(Unit)
                delay(100)
                recorded = ack
            },
        )
        val request = intent().copy(clockConfirmed = true)

        controller.start(request)
        recording.await()
        assertTrue(controller.stop(StopSource.FGS_TIMEOUT, request.id))
        controller.awaitCleanup(request.id)

        assertEquals(accepted, recorded)
        assertEquals(CollectionState.Stopped(request.id, StopSource.FGS_TIMEOUT), controller.state.value)
        assertFalse(Opcode.LIST in links.devices.single().requests)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aRefusedClockCreatesNoReceiptAndKeepsOriginalFilesFlowing() = runTest {
        val links = FakeLinks(backgroundScope)
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            CountingKeepAlive(),
            backgroundScope,
            onClockSet = { _, _ -> error("a refused clock cannot create receipt evidence") },
        )

        controller.start(intent().copy(clockConfirmed = true))
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(Opcode.SET_TIME in links.devices.single().requests)
        assertAllArchived(links)
    }

    @Test
    fun globalClockErrorEndsTheCollectionBeforeListingOrRecordingAnAnchor() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) {
            clockError = Frame.Error(0, ProtocolError.UNKNOWN_OPCODE.code, "")
        }
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onClockSet = { _, _ -> error("a global error cannot create clock receipt evidence") },
        )
        val request = intent().copy(clockConfirmed = true)

        controller.start(request)
        settle(controller)
        controller.awaitCleanup(request.id)

        val failed = assertIs<CollectionState.Failed>(controller.state.value)
        assertEquals(CollectionFailure.Device(ProtocolError.UNKNOWN_OPCODE.code, 0), failed.failure)
        assertEquals(1, links.connects)
        assertEquals(1, links.devices.single().requests.count { it == Opcode.SET_TIME })
        assertFalse(Opcode.LIST in links.devices.single().requests)
        assertTrue(index.records.isEmpty())
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(1, links.releases)
    }

    @Test
    fun terminalCallbackRetainsAStoppedRequestAcrossImmediateRestartAndIgnoresHostFaults() = runTest {
        val terminals = ArrayList<CollectionState>()
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope)
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onTerminal = {
                terminals += it
                error("host notification failed")
            },
        )
        val first = intent()
        val second = intent()

        controller.start(first)
        assertTrue(controller.stop(StopSource.USER, first.id))
        controller.start(second)
        // No coroutine observed the transient Stopped state; StateFlow now contains request two.
        assertEquals(second.id, controller.state.value.intentId)
        assertEquals(listOf<CollectionState>(CollectionState.Stopped(first.id, StopSource.USER)), terminals)
        settle(controller)

        assertEquals(2, terminals.size, "the old job's cleanup must not emit a second terminal")
        assertEquals(CollectionState.Stopped(first.id, StopSource.USER), terminals.first())
        assertEquals(second.id, assertIs<CollectionState.Done>(terminals.last()).intentId)
        assertTrue(keepAlive.allClosedOnce(), "a throwing callback never keeps protection held")
        assertEquals(terminals, listOf(controller.events.receive(), controller.events.receive()))
    }

    @Test
    fun settledOutcomesReachTheAdapterBeforeALaterSessionFailure() = runTest {
        val outcomes = ArrayList<FileOutcome>()
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope) {
            var opens = 0
            refuseOpen = { if (++opens == 2) Frame.Error(0, ProtocolError.AUTH_FAILED.code, "") else null }
        }
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onOutcome = { intent, outcome ->
                assertEquals(CollectionOrigin.USER_TAP, intent.origin)
                outcomes += outcome
            },
        )
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED))), controller.state.value)
        val saved = assertIs<FileOutcome.Synced>(outcomes.single())
        assertEquals(links.files.first().name, saved.name)
        assertEquals(links.files.first().bytes.size.toLong(), saved.bytes)
        assertFalse(saved.alreadyArchived)
        assertContentEquals(links.files.first().bytes, archived(links, saved.name))
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aFreshListingIsDeliveredEvenWhenTheArchiveDoesNotHaveRoom() = runTest {
        val links = FakeLinks(backgroundScope)
        val keepAlive = CountingKeepAlive()
        val collector = ArchiveCollector(
            store,
            index,
            SyncEngine(store, index, io = StandardTestDispatcher(testScheduler)),
            FakeStorageBudget(mapOf(StorageClass.Durable to 1L)),
        )
        var listed: List<Frame.File>? = null
        var end: Frame.ListEnd? = null
        val controller = CollectionController(
            "fake-0",
            links,
            collector,
            keepAlive,
            backgroundScope,
            onListed = { _, listing ->
                listed = listing.files
                end = listing.end
            },
        )
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(links.files.map { it.name }, listed?.map { it.name })
        assertEquals(links.files.size.toLong(), end?.count)
        val shortage = links.files.sumOf { it.bytes.size.toLong() } + ARCHIVE_HEADROOM_BYTES - 1L
        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Storage(StorageFailure.NO_SPACE, shortage)), controller.state.value)
        assertFalse(Opcode.OPEN in links.devices.single().requests)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun anAsynchronousProcessRefusalFailsOnlyItsRequest() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { connectDelayMs = { 5_000 } }
        val controller = controller(links, keepAlive)
        val first = intent()
        controller.start(first)
        advanceTimeBy(1_000)

        assertTrue(controller.keepAliveRefused(KeepAliveKind.PROCESS, first.id))
        controller.awaitCleanup(first.id)
        val refused = CollectionState.Failed(first.id, CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS))
        assertEquals(refused, controller.state.value)
        assertEquals(refused, controller.events.tryReceive().getOrNull())
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(1, links.releases)

        val second = intent()
        controller.start(second)
        assertFalse(controller.keepAliveRefused(KeepAliveKind.PROCESS, first.id))
        assertTrue(controller.state.value.active)
        assertEquals(second.id, controller.state.value.intentId)
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun aWorkersLanPolicyIsKeptOnEveryRetry() = runTest {
        val links = FakeLinks(backgroundScope, kind = TransportKind.LAN).apply {
            refuse = { n -> if (n == 1) DisconnectCause.Unreachable else null }
        }
        val controller = controller(links, CountingKeepAlive())
        val request = intent().copy(origin = CollectionOrigin.WORKER, linkPreference = LinkPreference.LAN)

        controller.start(request)
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(listOf(LinkPreference.LAN, LinkPreference.LAN), links.preferences)
    }

    @Test
    fun filesArchivedBeforeReconnectRemainInTheNewFileSummary() = runTest {
        val links = FakeLinks(backgroundScope) { n ->
            if (n == 1) beforeOpen = { name -> if (name == files[1].name) close() }
        }
        val controller = controller(links, CountingKeepAlive())

        controller.start(intent())
        settle(controller)

        val summary = assertIs<CollectionState.Done>(controller.state.value).summary
        assertEquals(2, links.connects)
        assertEquals(3, summary.newFiles)
        assertEquals(links.files.sumOf { it.bytes.size.toLong() }, summary.newBytes)
        assertEquals(3, summary.outcomes.size)
        assertAllArchived(links)
    }

    @Test
    fun foregroundTimeoutWinsAndCleanupWaitsForTheStoppedLendOnly() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, keepOpen = true).apply {
            connectDelayMs = { n -> if (n == 1) 5_000 else 10_000 }
            uninterruptible = true
        }
        val controller = controller(links, keepAlive)
        val first = intent()
        controller.start(first)
        advanceTimeBy(1_000)

        assertTrue(controller.stop(StopSource.FGS_TIMEOUT, first.id))
        assertEquals(CollectionState.Stopped(first.id, StopSource.FGS_TIMEOUT), controller.state.value)
        val second = intent()
        controller.start(second)
        val cleanup = async { controller.awaitCleanup(first.id) }
        runCurrent()
        assertFalse(cleanup.isCompleted)
        assertEquals(2, keepAlive.held(KeepAliveKind.PROCESS))

        advanceTimeBy(4_000)
        runCurrent()
        cleanup.await()
        assertEquals(1, links.releases)
        assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
        assertTrue(controller.state.value.active, "cleanup must not wait for the newer request")
        assertFalse(controller.stop(StopSource.FGS_TIMEOUT, first.id), "an old timeout cannot stop the new request")
        controller.stop(StopSource.USER, second.id)
        controller.awaitCleanup(second.id)
        assertTrue(keepAlive.allClosedOnce())
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
        assertEquals(2, waiting.retry)
        assertEquals(RetryPolicy().attempts, waiting.retryLimit)
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
    fun stopDuringFinalizationKeepsItsReasonAndReleasesProtectionAfterCancellation() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope, kind = TransportKind.LAN)
        val entered = CompletableDeferred<Unit>()
        val controller = CollectionController(
            "fake-0",
            links,
            collector(),
            keepAlive,
            backgroundScope,
            onFinalizing = { _, summary ->
                assertEquals(3, summary.newFiles)
                assertEquals(1, keepAlive.held(KeepAliveKind.PROCESS))
                assertEquals(1, keepAlive.held(KeepAliveKind.WIFI))
                entered.complete(Unit)
                delay(60_000)
            },
        )
        val request = intent()

        controller.start(request)
        entered.await()
        assertIs<CollectionState.Finalizing>(controller.state.value)
        assertTrue(controller.stop(StopSource.FGS_TIMEOUT, request.id))
        controller.awaitCleanup(request.id)

        assertEquals(CollectionState.Stopped(request.id, StopSource.FGS_TIMEOUT), controller.state.value)
        assertEquals(1, links.releases)
        assertTrue(keepAlive.allClosedOnce())
        assertEquals(CollectionState.Stopped(request.id, StopSource.FGS_TIMEOUT), controller.events.tryReceive().getOrNull())
        assertTrue(controller.events.tryReceive().isFailure)
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
            CollectionLink(link.session, link.thing, link.maxRead) {
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
    fun aThrowingLogNeverChangesTheOutcome() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { n -> if (n == 1) DisconnectCause.PeerReset else null } }
        val controller = controller(links, keepAlive) { throw IllegalStateException("log sink broke") }

        assertIs<StartResult.Started>(controller.start(intent()))
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(2, links.connects, "the loss was retried")
        assertTrue(logs.any { it.contains("start user_tap") }, logs.toString())
        assertTrue(keepAlive.allClosedOnce())
        assertIs<StartResult.Started>(controller.start(intent()))
        settle(controller)
        assertIs<CollectionState.Done>(controller.state.value)
        assertTrue(keepAlive.allClosedOnce())
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
            "thing=${links.thing}/year=2026/month=09/day=15/data_1000_b41d_15000-15089-90.parquet",
            FakeArchiveDevice.fakeParquet(7, 4_000_000),
        )
        val controller = controller(links, keepAlive)
        val request = intent()
        val waits = ArrayList<CollectionState.Waiting>()
        backgroundScope.launch { controller.state.collect { if (it is CollectionState.Waiting) waits += it } }

        controller.start(request)
        settle(controller)

        val failed = assertIs<CollectionState.Failed>(controller.state.value)
        assertEquals(request.id, failed.intentId)
        assertIs<CollectionFailure.Link>(failed.failure)
        // Progress resets the attempt bound, so only the 30 min ceiling ends it.
        assertTrue(links.connects > RetryPolicy().attempts, "${links.connects} connects")
        // The banner reads "attempt N of M". N counts attempts without progress, so it never passes M.
        assertTrue(waits.any { it.attempt > RetryPolicy().attempts }, "total attempts pass the bound")
        assertTrue(waits.all { it.retryLimit == RetryPolicy().attempts })
        assertTrue(waits.all { it.retry in 2..it.retryLimit }, waits.map { it.retry }.toString())
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
        val linksB = FakeLinks(backgroundScope, thing = "00000000-0000-4000-8000-000000000002")
        val linksC = FakeLinks(backgroundScope, thing = "00000000-0000-4000-8000-000000000003")
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
        val linksB = FakeLinks(backgroundScope, thing = "00000000-0000-4000-8000-000000000002") { chunkDelayMs = 5 }
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
            "thing=${links.thing}/year=2026/month=09/day=15/data_1030_b41d_15180-15269-90.parquet",
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
            "thing=${links.thing}/year=2026/month=09/day=15/data_1045_b41d_15270-15359-90.parquet",
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

        controller.start(intent(CollectionScope.One("thing=${links.thing}/year=2026/month=09/day=15/gone.parquet")))
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

    @Test
    fun anUnavailableTransportFailsAtOnceWithoutBackoff() = runTest {
        val keepAlive = CountingKeepAlive()
        val permits = Semaphore(1)
        val links = FakeLinks(backgroundScope).apply {
            fail = { TransportUnavailableException(UnavailableKind.RADIO_OFF, "bluetooth off") }
        }
        val controller = controller(links, keepAlive, permits = permits)
        val request = intent()

        controller.start(request)
        settle(controller)

        val failed = CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Unavailable(UnavailableKind.RADIO_OFF)))
        assertEquals(failed, controller.state.value)
        assertEquals(SyncEndReason.BLUETOOTH_OFF, controller.state.value.endReason())
        assertEquals(1, links.connects)
        assertEquals(0, testScheduler.currentTime, "no backoff wait")
        assertEquals(1, permits.availablePermits)
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun anUnreachableDeviceGetsOneConnectRetryThenFails() = runTest {
        val keepAlive = CountingKeepAlive()
        val links = FakeLinks(backgroundScope).apply { refuse = { DisconnectCause.Unreachable } }
        val controller = controller(links, keepAlive)
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(DisconnectCause.Unreachable)), controller.state.value)
        assertEquals(SyncEndReason.OUT_OF_RANGE, controller.state.value.endReason())
        assertEquals(2, links.connects)
        assertEquals(2_000, testScheduler.currentTime, "one 2 s backoff")
        assertTrue(keepAlive.allClosedOnce())
    }

    @Test
    fun anOpenTimeoutGetsOneConnectRetryThenFails() = runTest {
        val open = DisconnectCause.Timeout(TimeoutPhase.OPEN, 0)
        val links = FakeLinks(backgroundScope).apply { refuse = { open } }
        val controller = controller(links, CountingKeepAlive())
        val request = intent()

        controller.start(request)
        settle(controller)

        assertEquals(CollectionState.Failed(request.id, CollectionFailure.Link(open)), controller.state.value)
        assertEquals(2, links.connects)
    }

    @Test
    fun aConnectRetryThatReachesTheDeviceFinishes() = runTest {
        val links = FakeLinks(backgroundScope).apply { refuse = { n -> if (n == 1) DisconnectCause.Timeout(TimeoutPhase.OPEN, 0) else null } }
        val controller = controller(links, CountingKeepAlive())

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(SyncEndReason.COMPLETED, controller.state.value.endReason())
        assertAllArchived(links)
    }

    @Test
    fun aLossAfterProgressThenOneMissedConnectStillFinishes() = runTest {
        // Attempt 1 loses the link mid-run, attempt 2 cannot reach the device, attempt 3 finishes.
        val links = FakeLinks(backgroundScope) { n ->
            if (n == 1) beforeOpen = { name -> if (name == files[1].name) close() }
        }.apply { refuse = { n -> if (n == 2) DisconnectCause.Unreachable else null } }
        val controller = controller(links, CountingKeepAlive())

        controller.start(intent())
        settle(controller)

        assertIs<CollectionState.Done>(controller.state.value)
        assertEquals(3, links.connects)
        assertAllArchived(links)
    }

    @Test
    fun aWaitingDeviceLendsItsPermitToAReachableOne() = runTest {
        val permits = Semaphore(1)
        val keepAliveA = CountingKeepAlive()
        val keepAliveB = CountingKeepAlive()
        val linksA = FakeLinks(backgroundScope).apply { refuse = { n -> if (n == 1) DisconnectCause.Unreachable else null } }
        val linksB = FakeLinks(backgroundScope, thing = "00000000-0000-4000-8000-000000000002")
        val a = controller(linksA, keepAliveA, device = "fake-a", permits = permits)
        val b = controller(linksB, keepAliveB, device = "fake-b", permits = permits)

        a.start(intent(device = "fake-a"))
        b.start(intent(device = "fake-b"))
        advanceTimeBy(1_000)

        assertIs<CollectionState.Waiting>(a.state.value)
        assertEquals(1, keepAliveA.held(KeepAliveKind.PROCESS), "PROCESS stays held while waiting")
        assertEquals(1, linksB.connects, "B connects while A backs off")
        settle(a)
        settle(b)

        assertIs<CollectionState.Done>(a.state.value)
        assertIs<CollectionState.Done>(b.state.value)
        assertEquals(2, linksA.connects)
        assertEquals(1, permits.availablePermits)
        assertTrue(keepAliveA.allClosedOnce() && keepAliveB.allClosedOnce())
    }

    @Test
    fun aStopWhileWaitingForThePermitAgainReleasesNothingTwice() = runTest {
        val permits = Semaphore(1)
        val linksA = FakeLinks(backgroundScope).apply { refuse = { n -> if (n == 1) DisconnectCause.Unreachable else null } }
        val linksB = FakeLinks(backgroundScope, thing = "00000000-0000-4000-8000-000000000002") { listDelayMs = 10_000 }
        val keepAliveA = CountingKeepAlive()
        val a = controller(linksA, keepAliveA, device = "fake-a", permits = permits)
        val b = controller(linksB, CountingKeepAlive(), device = "fake-b", permits = permits)
        val requestA = intent(device = "fake-a")

        a.start(requestA)
        runCurrent()
        b.start(intent(device = "fake-b"))
        // A's backoff ends while B holds the slot, so A queues for it.
        advanceTimeBy(2_500)
        assertTrue(b.state.value.active)
        assertTrue(a.stop(StopSource.USER))
        settle(a)
        settle(b)

        assertEquals(CollectionState.Stopped(requestA.id, StopSource.USER), a.state.value)
        assertEquals(SyncEndReason.STOPPED, a.state.value.endReason())
        assertEquals(1, linksA.connects)
        assertEquals(1, permits.availablePermits)
        assertTrue(keepAliveA.allClosedOnce())
    }

    @Test
    fun connectPhaseFailuresHaveTheirOwnBound() {
        val policy = RetryPolicy(attempts = 5, connectRetries = 1)
        val open = DisconnectCause.Timeout(TimeoutPhase.OPEN, 0)
        assertEquals(2.seconds, policy.backoff(DisconnectCause.Unreachable, 2, 0.seconds, connectFailures = 1))
        assertEquals(null, policy.backoff(DisconnectCause.Unreachable, 3, 0.seconds, connectFailures = 2))
        assertEquals(null, policy.backoff(open, 3, 0.seconds, connectFailures = 2))
        assertEquals(4.seconds, policy.backoff(DisconnectCause.PeerReset, 3, 0.seconds, connectFailures = 0))
        assertTrue(connectPhase(open))
        assertFalse(connectPhase(DisconnectCause.Timeout(TimeoutPhase.FIRST_FRAME, Opcode.LIST)))
        assertFalse(connectPhase(DisconnectCause.PeerReset))
        assertFailsWith<IllegalArgumentException> { RetryPolicy(connectRetries = -1) }
    }

    @Test
    fun aTerminalStateNamesItsEndReason() {
        assertEquals(null, CollectionState.Idle.endReason())
        assertEquals(SyncEndReason.STOPPED, CollectionState.Stopped(1, StopSource.USER).endReason())
        assertEquals(SyncEndReason.STOPPED, CollectionState.Stopped(1, StopSource.APP).endReason())
        assertEquals(SyncEndReason.INTERRUPTED, CollectionState.Stopped(1, StopSource.SYSTEM).endReason())
        assertEquals(SyncEndReason.INTERRUPTED, CollectionState.Stopped(1, StopSource.FGS_TIMEOUT).endReason())
        assertEquals(
            SyncEndReason.TOKEN_ROTATED,
            CollectionState.Failed(1, CollectionFailure.Link(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED))).endReason(),
        )
        assertEquals(SyncEndReason.NO_SPACE, CollectionState.Failed(1, CollectionFailure.Storage(StorageFailure.NO_SPACE)).endReason())
    }
}
