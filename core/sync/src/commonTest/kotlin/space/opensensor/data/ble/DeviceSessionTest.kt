package space.opensensor.data.ble

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.LanConfig
import space.opensensor.data.protocol.ListCursor
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.protocol.Request
import space.opensensor.data.protocol.toHex
import space.opensensor.data.sync.SmartSyncPolicy
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkEvent
import space.opensensor.link.LinkSession
import space.opensensor.link.LinkState
import space.opensensor.link.ScriptedLinkDriver
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A phone time after the clock policy build floor, on a whole second. */
private const val PHONE_NOW = SmartSyncPolicy.PHONE_CLOCK_FLOOR_MILLIS + 86_400_000L

class DeviceSessionTest {
    private val opened = Frame.Opened(1, 12, 0, "legacy-parquet/boundary.parquet")

    /**
     * One peer behind one open [LinkSession]. [reply] answers each write, [bodies] keeps every
     * request body in order. [view] builds a typed [DeviceSession] over the shared link.
     */
    private class Peer(scope: CoroutineScope, timeSource: TimeSource.WithComparableMarks, statusSnapshot: ByteArray? = null) {
        val bodies = mutableListOf<ByteArray>()
        val events = mutableListOf<LinkEvent>()
        var reply: ScriptedLinkDriver.(op: Int, body: ByteArray) -> Unit = { _, _ -> }
        val driver = ScriptedLinkDriver { op, body ->
            bodies += body
            reply(op, body)
        }
        val transport = StubTransport(statusSnapshot)
        val link = LinkSession(driver, scope, timeSource = timeSource, log = { events += it })

        fun view() = DeviceSession(link, transport, FrameRecorder())
    }

    private suspend fun TestScope.peer(statusSnapshot: ByteArray? = null) =
        Peer(backgroundScope, testScheduler.timeSource, statusSnapshot).also { it.link.open() }

    // ------------------------------------------------------------------ LIST

    @Test
    fun listCountMismatchFailsAndLeavesTheLinkReadyForTheNextList() = runTest {
        val p = peer()
        val session = p.view()
        p.reply = { op, _ -> if (op == Opcode.LIST) emit(Frame.File(12, opened.name), Frame.ListEnd(2, 0, 1024, 0)) }
        assertFailsWith<ProtocolViolationException> { session.list() }
        assertIs<LinkState.Ready>(p.link.state.value)

        p.reply = { op, _ -> if (op == Opcode.LIST) emit(Frame.File(12, opened.name), Frame.ListEnd(1, 0, 1024, 0)) }
        val (files, end) = session.list()
        assertEquals(listOf(Frame.File(12, opened.name)), files)
        assertEquals(1L, end.count)
        // LIST_END was consumed as the terminal, so no drain and no ABORT ran in between.
        assertEquals(listOf(Opcode.LIST, Opcode.LIST), p.driver.sent)
        assertTrue(p.events.none { it is LinkEvent.Abandoned })
    }

    @Test
    fun listSinceCountMismatchFailsAndLeavesTheLinkReadyForTheNextListSince() = runTest {
        val p = peer()
        val session = p.view()
        val next = ListCursor(ByteArray(ListCursor.BYTES) { (it + 1).toByte() })
        p.reply = { op, _ ->
            if (op == Opcode.LIST_SINCE) emit(Frame.File(12, opened.name), Frame.ListSinceEnd(2, 0, 1024, 0, 0, next))
        }
        assertFailsWith<ProtocolViolationException> { session.listSince(ListCursor.ZERO) }
        assertIs<LinkState.Ready>(p.link.state.value)

        p.reply = { op, _ ->
            if (op == Opcode.LIST_SINCE) emit(Frame.File(12, opened.name), Frame.File(9, "b.parquet"), Frame.ListSinceEnd(1, 0, 1024, 0, 0, next))
        }
        assertFailsWith<ProtocolViolationException> { session.listSince(ListCursor.ZERO) }
        assertIs<LinkState.Ready>(p.link.state.value)

        p.reply = { op, _ ->
            if (op == Opcode.LIST_SINCE) emit(Frame.File(12, opened.name), Frame.ListSinceEnd(1, 0, 1024, 0, 0, next))
        }
        val (files, end) = session.listSince(ListCursor.ZERO)
        assertEquals(listOf(Frame.File(12, opened.name)), files)
        assertEquals(next, end.next)
        assertEquals(listOf(Opcode.LIST_SINCE, Opcode.LIST_SINCE, Opcode.LIST_SINCE), p.driver.sent)
        assertTrue(p.events.none { it is LinkEvent.Abandoned })
    }

    @Test
    fun tooManyFileFramesFailInsteadOfAcceptingAnUnreliableListing() = runTest {
        val p = peer()
        p.reply = { op, _ -> if (op == Opcode.LIST) emit(Frame.File(12, opened.name), Frame.ListEnd(0, 0, 1024, 0)) }
        assertFailsWith<ProtocolViolationException> { p.view().list() }
        assertIs<LinkState.Ready>(p.link.state.value)
    }

    @Test
    fun listOfSeventyThousandFilesDecodesTheU32Count() = runTest {
        val p = peer()
        val n = 70_000
        val listing = async { p.view().list() }
        runCurrent()
        assertEquals(listOf(Opcode.LIST), p.driver.sent)
        // Batches keep the unconsumed frames well inside the LAN inbox budget.
        var at = 0
        while (at < n) {
            val until = minOf(at + 5_000, n)
            for (i in at until until) p.driver.emit(Frame.File(i.toLong(), "f$i"))
            at = until
            runCurrent()
        }
        p.driver.emit(Frame.ListEnd(n.toLong(), 0, 0, 0))
        runCurrent()
        val (files, end) = listing.await()
        assertEquals(70_000L, end.count)
        assertEquals(n, files.size)
        assertEquals("f69999", files.last().name)
        assertIs<LinkState.Ready>(p.link.state.value)
    }

    // ------------------------------------------------------------------ waiting is event driven

    /** Counts every dispatch, so a test can prove a wait never wakes up on its own. */
    @OptIn(InternalCoroutinesApi::class)
    private class CountingDispatcher(private val inner: CoroutineDispatcher) :
        CoroutineDispatcher(),
        Delay by (inner as Delay) {
        var dispatches = 0
        override fun isDispatchNeeded(context: CoroutineContext) = inner.isDispatchNeeded(context)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            inner.dispatch(context, block)
        }
    }

    @Test
    fun openWaitsForItsReplyWithoutPolling() = runTest {
        // Every coroutine of the link and of the caller runs on a dispatcher that counts each
        // resumption. The old session woke every 250 ms to poll, so 59 s of silence would have
        // cost about 236 dispatches. Here the caller suspends on its inbox and the router on its
        // event channel, and the 60 s OPEN timer is a single scheduled delay. The count must not
        // move until a frame arrives.
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val driver = ScriptedLinkDriver()
        val link = LinkSession(driver, backgroundScope + counting, timeSource = testScheduler.timeSource)
        link.open()
        val session = DeviceSession(link, StubTransport(), FrameRecorder())
        val request = async(counting) { session.open(opened.name) }
        runCurrent()
        assertEquals(listOf(Opcode.OPEN), driver.sent)

        val before = counting.dispatches
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(before, counting.dispatches, "a request waiting for its reply must not wake up")
        assertFalse(request.isCompleted)

        driver.emit(opened)
        runCurrent()
        assertEquals(opened, request.await())
        assertEquals(listOf(Opcode.OPEN), driver.sent)
    }

    // ------------------------------------------------------------------ cancellation and views

    @Test
    fun cancelledOpenOnOneViewIsDrainedBeforeAnotherViewLists() = runTest {
        val p = peer()
        val a = p.view()
        val b = p.view()
        p.reply = { op, _ ->
            if (op == Opcode.LIST) emit(Frame.File(10, "a.parquet"), Frame.File(20, "b.parquet"), Frame.ListEnd(2, 0, 0, 0))
        }
        val run = async { a.open(opened.name) }
        runCurrent()
        run.cancelAndJoin()

        val listing = async { b.list() }
        runCurrent()
        assertEquals(listOf(Opcode.OPEN, Opcode.ABORT), p.driver.sent, "LIST waits for the abandoned OPEN")

        // The late OPENED of the cancelled run, then the answer to ABORT.
        p.driver.emit(opened, Frame.Aborted(0))
        runCurrent()
        val (files, end) = listing.await()
        assertEquals(listOf("a.parquet", "b.parquet"), files.map { it.name })
        assertEquals(2L, end.count)
        assertEquals(listOf(Opcode.OPEN, Opcode.ABORT, Opcode.LIST), p.driver.sent)
        assertTrue(p.events.any { it is LinkEvent.Drained && it.op == Opcode.OPEN })
        assertTrue(p.events.none { it is LinkEvent.ForeignDropped })
    }

    @Test
    fun lostLinkFailsAWaitingRequestAtOnce() = runTest {
        val p = peer()
        val request = async { runCatching { p.view().open(opened.name) } }
        runCurrent()
        val at = currentTime
        p.driver.eof()
        runCurrent()
        assertIs<LinkClosedException>(request.await().exceptionOrNull())
        assertEquals(at, currentTime)
    }

    @Test
    fun overATransportFailsAWaitingRequestWhenTheTransportDisconnects() = runTest {
        val transport = StubTransport()
        val session = DeviceSession.over(transport, backgroundScope, info = { DeviceInfo(proto = 3) })
        session.link.open()
        val request = async { runCatching { session.open(opened.name) } }
        runCurrent()
        assertContentEquals(Request.Open(opened.name).encode(), transport.writes.single())
        transport.connectionState.value = ConnectionState.Disconnected
        runCurrent()
        assertIs<LinkClosedException>(request.await().exceptionOrNull())
        assertIs<LinkState.Closed>(session.link.state.value)
    }

    @Test
    fun overATransportDeliversReplyFramesFromItsChannel() = runTest {
        val transport = StubTransport()
        val session = DeviceSession.over(transport, backgroundScope, info = { DeviceInfo(proto = 3) })
        session.link.open()
        val request = async { session.open(opened.name) }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(opened))
        runCurrent()
        assertEquals(opened, request.await())
    }

    // ------------------------------------------------------------------ READ

    @Test
    fun windowLostThrowsOnlyAfterReadEndAndLateFramesNeverReachTheNextRead() = runTest {
        val p = peer()
        val session = p.view()
        val got = mutableListOf<Long>()
        val window = async { runCatching { session.read(1, 0, 16) { got += it.offset } } }
        runCurrent()
        // Bytes 4..8 never arrive: the second CHUNK skips ahead.
        p.driver.emit(Frame.Chunk(1, 0, ByteArray(4) { 1 }), Frame.Chunk(1, 8, ByteArray(4) { 2 }), Frame.Chunk(1, 12, ByteArray(4) { 3 }))
        runCurrent()
        assertFalse(window.isCompleted, "the window is consumed through READ_END before it fails")
        p.driver.emit(Frame.ReadEnd(1, 16, 0))
        runCurrent()
        val lost = assertIs<WindowLostException>(window.await().exceptionOrNull())
        assertEquals(4L, lost.receivedUpTo)
        assertEquals(listOf(0L), got)
        assertTrue(p.events.none { it is LinkEvent.Abandoned }, "READ_END was consumed, nothing to drain")

        // A stray CHUNK and READ_END of the old window land while the link is idle.
        p.driver.emit(Frame.Chunk(1, 8, ByteArray(4) { 9 }), Frame.ReadEnd(1, 16, 0))
        runCurrent()

        val retried = mutableListOf<Frame.Chunk>()
        val retry = async { session.read(1, lost.receivedUpTo, 12) { retried += it } }
        runCurrent()
        p.driver.emit(Frame.Chunk(1, 4, ByteArray(4) { 4 }), Frame.Chunk(1, 8, ByteArray(8) { 5 }), Frame.ReadEnd(1, 16, 0))
        runCurrent()
        assertEquals(Frame.ReadEnd(1, 16, 0), retry.await())
        assertEquals(listOf(4L, 8L), retried.map { it.offset })
        assertContentEquals(ByteArray(4) { 4 } + ByteArray(8) { 5 }, retried.fold(ByteArray(0)) { acc, c -> acc + c.payload })
        assertEquals(listOf(Opcode.READ, Opcode.READ), p.driver.sent)
    }

    @Test
    fun readEndThatDisagreesWithTheReceivedBytesIsWindowLost() = runTest {
        val p = peer()
        p.reply = { op, _ -> if (op == Opcode.READ) emit(Frame.Chunk(1, 0, ByteArray(4)), Frame.ReadEnd(1, 8, 0)) }
        val lost = assertFailsWith<WindowLostException> { p.view().read(1, 0, 8) { } }
        assertEquals(4L, lost.receivedUpTo)
        assertIs<LinkState.Ready>(p.link.state.value)
    }

    @Test
    fun chunkForAnotherHandleIsAProtocolViolation() = runTest {
        val p = peer()
        p.reply = { op, _ -> if (op == Opcode.READ) emit(Frame.Chunk(2, 0, ByteArray(4)), Frame.ReadEnd(1, 4, 0)) }
        assertFailsWith<ProtocolViolationException> { p.view().read(1, 0, 4) { } }
    }

    // ------------------------------------------------------------------ raw command

    @Test
    fun rawCommandReturnsTheExactBytesThroughTheTerminal() = runTest {
        val p = peer()
        val file = FrameCodec.encode(Frame.File(12, opened.name))
        val end = FrameCodec.encode(Frame.ListEnd(1, 0, 1024, 0))
        p.reply = { op, _ ->
            if (op == Opcode.LIST) {
                sink.onFrame(file)
                sink.onFrame(end)
            }
        }
        val got = p.view().rawCommand(Request.List.encode())
        assertEquals(2, got.size)
        assertContentEquals(file, got[0].first)
        assertContentEquals(end, got[1].first)
        assertEquals(listOf<Frame?>(Frame.File(12, opened.name), Frame.ListEnd(1, 0, 1024, 0)), got.map { it.second })
        assertContentEquals(Request.List.encode(), p.bodies.single())
        assertEquals(0L, currentTime, "a terminal frame ends the raw command without waiting for quiet")
    }

    @Test
    fun rawCommandRefusesRebootAndSetConfigWithoutWriting() = runTest {
        val p = peer()
        val session = p.view()
        for (request in listOf(Request.Reboot.encode(), byteArrayOf(Opcode.SET_CONFIG.toByte(), 0x7b, 0x7d), byteArrayOf(Opcode.SET_TIME.toByte()))) {
            val refused = assertFailsWith<RawCommandRefusedException> { session.rawCommand(request) }
            assertEquals(request[0].toInt() and 0xFF, refused.op)
        }
        assertTrue(p.driver.sent.isEmpty())
    }

    @Test
    fun rawCommandReturnsWhatArrivedWhenQuietAndTheRestIsDrained() = runTest {
        val p = peer()
        val session = p.view()
        val request = async { session.rawCommand(Request.List.encode(), quiet = 250.milliseconds) }
        runCurrent()
        p.driver.emit(Frame.File(1, "a.parquet"))
        runCurrent()
        advanceTimeBy(251.milliseconds)
        runCurrent()
        assertEquals(listOf<Frame?>(Frame.File(1, "a.parquet")), request.await().map { it.second })
        assertEquals(listOf(Opcode.LIST, Opcode.ABORT), p.driver.sent)

        // The rest of the quiet reply never reaches the next request.
        p.reply = { op, _ -> if (op == Opcode.LIST) emit(Frame.File(2, "b.parquet"), Frame.ListEnd(1, 0, 0, 0)) }
        val next = async { session.list() }
        runCurrent()
        p.driver.emit(Frame.File(3, "late.parquet"), Frame.ListEnd(2, 0, 0, 0), Frame.Aborted(0))
        runCurrent()
        assertEquals(listOf("b.parquet"), next.await().first.map { it.name })
    }

    @Test
    fun rawCommandWithNoReplyReturnsNothingAfterQuiet() = runTest {
        val p = peer()
        val request = async { p.view().rawCommand(Request.Open(opened.name).encode(), quiet = 250.milliseconds) }
        runCurrent()
        advanceTimeBy(251.milliseconds)
        runCurrent()
        assertTrue(request.await().isEmpty())
        assertIs<LinkState.Ready>(p.link.state.value)
    }

    // ------------------------------------------------------------------ config

    @Test
    fun getConfigReassemblesOrderedBleChunksBeforeParsingJson() = runTest {
        val p = peer()
        val json = """{"sensor":{"vendor":"Plantower","model":"PMS5003T","serial":"PMS5003T-202604081332","batch_candidate":1}}"""
        val bytes = json.encodeToByteArray()
        p.reply = { op, _ ->
            if (op == Opcode.GET_CONFIG) {
                emit(Frame.ConfigChunk(0, bytes.size, 0, bytes.copyOfRange(0, 40)), Frame.ConfigChunk(0, bytes.size, 40, bytes.copyOfRange(40, bytes.size)))
            }
        }
        assertEquals("PMS5003T-202604081332", p.view().getConfig().config.sensor?.serial)
    }

    @Test
    fun getConfigRejectsMissingChunkInsteadOfAcceptingPartialJson() = runTest {
        val p = peer()
        p.reply = { op, _ ->
            if (op == Opcode.GET_CONFIG) emit(Frame.ConfigChunk(0, 10, 0, byteArrayOf(1, 2)), Frame.ConfigChunk(0, 10, 3, byteArrayOf(3, 4)))
        }
        assertFailsWith<ProtocolViolationException> { p.view().getConfig() }
    }

    @Test
    fun locationUsesTheExistingConfigChunkEnvelopeAndLegacyIsUnsupported() = runTest {
        val p = peer()
        val session = p.view()
        val json = """{"location":{"cell":"85283083fffffff","resolution":5,"country":"US"}}"""
        val bytes = json.encodeToByteArray()
        p.reply = { op, _ ->
            if (op == Opcode.GET_CONFIG) {
                emit(Frame.ConfigChunk(0, bytes.size, 0, bytes.copyOfRange(0, 40)), Frame.ConfigChunk(0, bytes.size, 40, bytes.copyOfRange(40, bytes.size)))
            }
        }
        assertEquals("85283083fffffff", session.getLocation()?.cell)
        assertContentEquals(Request.GetLocation.encode(), p.bodies.single())
        p.reply = { op, _ -> if (op == Opcode.GET_CONFIG) emit(Frame.Config(0, "{}")) }
        assertNull(session.getLocation())
    }

    // ------------------------------------------------------------------ clock

    @Test
    fun automaticClockUsesAcknowledgedPrecisionAndDoesNotInventLegacyFractions() = runTest {
        val p = peer()
        val request = async { p.view().synchronizeClock { 1_789_581_931_987L } }
        runCurrent()
        assertContentEquals(Request.SetTime(1_789_581_931L, 987_000).encode(), p.bodies.single())
        p.driver.emit(Frame.TimeSet(1_789_581_931L, 500))
        assertEquals(1_789_581_931_000_000_000L, request.await().epochNanos)
    }

    @Test
    fun automaticClockRetriesOnlyMalformedExtensionWithFreshLegacySeconds() = runTest {
        val p = peer()
        var now = 1_789_581_931_987L
        val request = async { p.view().synchronizeClock { now } }
        runCurrent()
        now += 1000
        p.driver.emit(Frame.Error(Opcode.SET_TIME, ProtocolError.MALFORMED_REQUEST.code, "legacy length"))
        runCurrent()
        assertContentEquals(Request.SetTime(1_789_581_932L).encode(), p.bodies.last())
        p.driver.emit(Frame.TimeSet(1_789_581_932L, 501))
        assertEquals(501L, request.await().monotonicMicros)
        assertEquals(2, p.bodies.size)
    }

    @Test
    fun aRefusedClockIsNotRetriedAsALegacyRequest() = runTest {
        val p = peer()
        val request = async { runCatching { p.view().synchronizeClock { 1_789_581_931_987L } } }
        runCurrent()
        p.driver.emit(Frame.Error(Opcode.SET_TIME, 9, "refused"))
        assertIs<DeviceErrorException>(request.await().exceptionOrNull())
        assertEquals(1, p.bodies.size)
    }

    @Test
    fun maintenanceReadsFreshStatusAndRetainsARecentExternalClock() = runTest {
        val p = peer()
        val session = p.view()
        for (source in listOf(1, 3)) {
            val request = async { session.synchronizeClockIfNeeded { PHONE_NOW } }
            runCurrent()
            assertContentEquals(Request.Status.encode(), p.bodies.last())
            p.transport.statusNotifications.emit("{\"utc\":1,\"clk\":$source,\"utc_ms\":$PHONE_NOW,\"clk_age_s\":60}".encodeToByteArray())
            assertNull(request.await())
        }
        assertEquals(2, p.bodies.size)
    }

    @Test
    fun maintenanceAnchorsMissingRtcAndOldExternalClocks() = runTest {
        for (json in listOf("{\"utc\":0}", "{\"utc\":1,\"clk\":2}", "{\"utc\":1,\"clk\":1,\"clk_age_s\":21600}")) {
            val p = peer()
            val request = async { p.view().synchronizeClockIfNeeded { PHONE_NOW } }
            runCurrent()
            p.transport.statusNotifications.emit(json.encodeToByteArray())
            runCurrent()
            assertContentEquals(Request.SetTime(PHONE_NOW / 1000, 0).encode(), p.bodies.last())
            val ack = Frame.TimeSet(PHONE_NOW / 1000, 501L, 0)
            p.driver.emit(ack)
            assertEquals(ack, request.await())
        }
    }

    @Test
    fun maintenanceFallsBackForFirmwareWithoutStatusNotifications() = runTest {
        val p = peer()
        val request = async { p.view().synchronizeClockIfNeeded { PHONE_NOW } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertContentEquals(Request.SetTime(PHONE_NOW / 1000, 0).encode(), p.bodies.last())
        p.driver.emit(Frame.TimeSet(PHONE_NOW / 1000, 501L, 0))
        assertEquals(501L, request.await()?.monotonicMicros)
    }

    @Test
    fun legacyStatusReadFallbackRetainsAnAlreadyUsableUtcClock() = runTest {
        val p = peer("{\"utc\":1}".encodeToByteArray())
        val request = async { p.view().synchronizeClockIfNeeded { PHONE_NOW } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(request.await())
        assertContentEquals(Request.Status.encode(), p.bodies.single())
    }

    @Test
    fun aCachedFallbackCannotBeMistakenForClockDrift() = runTest {
        val p = peer("{\"utc\":1,\"clk\":1,\"utc_ms\":${PHONE_NOW - 120_000},\"clk_age_s\":60}".encodeToByteArray())
        val request = async { p.view().synchronizeClockIfNeeded { PHONE_NOW } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(request.await())
        assertEquals(1, p.bodies.size)
    }

    @Test
    fun aPhoneClockBeforeTheBuildFloorNeverSetsTime() = runTest {
        val p = peer()
        val request = async { p.view().synchronizeClockIfNeeded { SmartSyncPolicy.PHONE_CLOCK_FLOOR_MILLIS - 1 } }
        runCurrent()
        p.transport.statusNotifications.emit("{\"utc\":0}".encodeToByteArray())
        assertNull(request.await())
        assertContentEquals(Request.Status.encode(), p.bodies.single())
    }

    @Test
    fun theAutomaticClockCheckRunsOncePerConnectionEvenAfterATimeout() = runTest {
        val p = peer()
        // The first check times out on STATUS and has no cached snapshot, so it sends SET_TIME and that times out too.
        val first = async { runCatching { p.view().synchronizeClockOnce { PHONE_NOW } } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        first.await()
        val sent = p.bodies.size
        // A second run or screen on the same link never asks again, so a slow sensor is not looped.
        assertNull(p.view().synchronizeClockOnce { PHONE_NOW })
        assertEquals(sent, p.bodies.size)
    }

    @Test
    fun wifiSyncIsSwitchedBackOnOncePerConnectionBelowDeveloper() = runTest {
        val p = peer()
        p.reply = { op, _ -> if (op == Opcode.SET_CONFIG) emit(Frame.Config(0, "{\"lan\":{\"on\":1}}")) }
        val off = DeviceConfig(lan = LanConfig(on = 0))
        assertNull(p.view().enableLanOnce(off, developer = true))
        assertNull(p.view().enableLanOnce(DeviceConfig(lan = LanConfig(on = 1)), developer = false))
        assertNull(p.view().enableLanOnce(DeviceConfig(), developer = false))
        assertTrue(p.bodies.isEmpty())
        assertEquals(1, p.view().enableLanOnce(off, developer = false)?.config?.lan?.on)
        assertContentEquals(Request.SetConfig(ConfigPatch.build { lanOn(true) }).encode(), p.bodies.single())
        assertNull(p.view().enableLanOnce(off, developer = false))
        assertEquals(1, p.bodies.size)
    }

    @Test
    fun maintenanceCancellationNeverSetsTime() = runTest {
        val p = peer()
        val request = async { p.view().synchronizeClockIfNeeded { PHONE_NOW } }
        runCurrent()
        request.cancelAndJoin()
        runCurrent()
        assertContentEquals(Request.Status.encode(), p.bodies.single())
        assertIs<LinkState.Ready>(p.link.state.value)
    }

    @Test
    fun aBondThatTakesLongerThanAMinuteStillOpens() = runTest {
        val transport = StubTransport(connectDelay = 150.seconds)
        val session = DeviceSession.over(transport, backgroundScope, info = { DeviceInfo(proto = 3) })

        session.link.open()

        assertIs<LinkState.Ready>(session.link.state.value)
    }

    @Test
    fun aTransportFailureWhileOpeningKeepsItsOwnMessage() = runTest {
        val failure = TransportException("Pairing timed out", gattStatus = 5)
        val session = DeviceSession.over(StubTransport(connectError = failure), backgroundScope, info = { DeviceInfo(proto = 3) })

        val e = assertFailsWith<LinkClosedException> { session.link.open() }

        assertEquals("Pairing timed out", e.message)
        assertEquals(5, e.gattStatus)
        assertIs<LinkState.Closed>(session.link.state.value)
    }

    @Test
    fun aMalformedInfoReplyFailsTheOpenAsAFramingViolation() = runTest {
        val session = DeviceSession.over(StubTransport(infoReply = "not json".encodeToByteArray()), backgroundScope)

        val e = assertFailsWith<LinkClosedException> { session.link.open() }

        assertIs<DisconnectCause.FramingViolation>(e.disconnectCause)
        assertIs<LinkState.Closed>(session.link.state.value)
    }

    @Test
    fun aFailedInfoReadFailsTheOpenAsAFramingViolation() = runTest {
        val session = DeviceSession.over(StubTransport(), backgroundScope)

        val e = assertFailsWith<LinkClosedException> { session.link.open() }

        assertIs<DisconnectCause.FramingViolation>(e.disconnectCause)
    }

    // ------------------------------------------------------------------ GET_DESCRIPTOR

    /** Answers `GET_DESCRIPTOR` from [template] the way the firmware clamps it. */
    private fun ScriptedLinkDriver.serveDescriptor(template: ByteArray, payloadMax: Int, body: ByteArray) {
        val offset = (body[1].toLong() and 0xFF) or ((body[2].toLong() and 0xFF) shl 8) or
            ((body[3].toLong() and 0xFF) shl 16) or ((body[4].toLong() and 0xFF) shl 24)
        val length = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        val n = minOf(length.toLong(), template.size - offset, (payloadMax - 9).toLong()).toInt()
        emit(Frame.DescriptorChunk(template.size.toLong(), offset, template.copyOfRange(offset.toInt(), offset.toInt() + n)))
    }

    @Test
    fun descriptorDownloadsInChunksSizedByPayloadMax() = runTest {
        val template = ByteArray(600) { it.toByte() }
        val p = peer()
        p.reply = { op, body -> if (op == Opcode.GET_DESCRIPTOR) serveDescriptor(template, 244, body) }
        assertContentEquals(template, p.view().downloadDescriptor())
        // The scripted link reports payload_max 244, so each request asks for 235 bytes.
        assertEquals(listOf("11000000" + "00eb00", "11eb000000eb00", "11d6010000eb00"), p.bodies.map { it.toHex() })
    }

    @Test
    fun descriptorErrorSeventeenIsNoDescriptor() = runTest {
        val p = peer()
        p.reply = { op, _ -> if (op == Opcode.GET_DESCRIPTOR) emit(Frame.Error(Opcode.GET_DESCRIPTOR, ProtocolError.NO_DESCRIPTOR.code, "")) }
        assertFailsWith<NoDescriptorException> { p.view().downloadDescriptor() }
    }

    @Test
    fun descriptorIsNeverRequestedBeforeTheLinkIsOpen() = runTest {
        val p = Peer(backgroundScope, testScheduler.timeSource)
        val e = assertFailsWith<IllegalStateException> { p.view().downloadDescriptor() }
        assertEquals("GET_DESCRIPTOR needs an open link", e.message)
        assertTrue(p.bodies.isEmpty())
        assertTrue(p.driver.sent.isEmpty())
    }

    /** The parts of [DeviceTransport] a [DeviceSession] still reads: pushes, the status read, and writes for [DeviceSession.over]. */
    private class StubTransport(
        private val statusSnapshot: ByteArray? = null,
        private val connectDelay: Duration = Duration.ZERO,
        private val connectError: TransportException? = null,
        private val infoReply: ByteArray? = null,
    ) : DeviceTransport {
        override val address = "test-device"
        override val displayName = "Test device"
        override val kind = TransportKind.FAKE
        override val endpoint = address
        override val mtu = 517
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))
        override val responseFrames = Channel<ByteArray>(Channel.UNLIMITED)
        override val statusNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        val writes = mutableListOf<ByteArray>()
        override suspend fun connect() {
            delay(connectDelay)
            connectError?.let { throw it }
        }
        override suspend fun disconnect() {
            connectionState.value = ConnectionState.Disconnected
        }
        override suspend fun readInfo(): ByteArray = infoReply ?: throw TransportException("info read failed")
        override suspend fun readStatus(): ByteArray = statusSnapshot ?: error("no status snapshot")
        override suspend fun readLive(): ByteArray = error("unused")
        override suspend fun writeControl(request: ByteArray) {
            writes += request
        }
        override fun close() {
            responseFrames.close()
        }
    }
}
