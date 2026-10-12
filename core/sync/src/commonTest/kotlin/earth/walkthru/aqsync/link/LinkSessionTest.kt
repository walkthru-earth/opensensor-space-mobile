package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DisconnectReason
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.Request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class LinkSessionTest {
    private val events = mutableListOf<LinkEvent>()

    private fun TestScope.session(
        driver: LinkDriver,
        heartbeat: Heartbeat? = null,
        budget: Long = LinkSession.LAN_BUDGET,
    ) = LinkSession(
        driver = driver,
        scope = backgroundScope,
        heartbeat = heartbeat,
        inboxBudget = budget,
        timeSource = testScheduler.timeSource,
        log = { events += it },
    )

    private fun files(n: Int) = Array<Frame>(n) { Frame.File(1024, "f$it.parquet") }

    @Test
    fun cancelledListWithBacklogLeavesLinkReadyThroughTenIdleMinutes() = runTest {
        var pings = 0
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.STATUS -> status("""{"n":${++pings}}""")
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver, heartbeat = Heartbeat.LAN)
        s.open()

        val list = launch { s.collect(Request.List) }
        runCurrent()
        driver.emit(*files(5))
        runCurrent()
        list.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(Opcode.LIST, Opcode.ABORT), driver.sent)

        // An F-A peer keeps streaming the reply and answers ABORT with ABORTED 0 afterwards.
        driver.emit(*files(300), Frame.ListEnd(305, 0, 0, 0), Frame.Aborted(0))
        runCurrent()

        repeat(20) {
            advanceTimeBy(30.seconds)
            runCurrent()
        }
        assertIs<LinkState.Ready>(s.state.value)
        assertTrue(pings >= 4, "pings $pings")
        assertEquals("""{"n":$pings}""", s.status.value?.decodeToString())

        assertEquals(listOf<Frame>(Frame.Config(0, "{}")), s.collect(Request.GetConfig))
        assertTrue(events.any { it is LinkEvent.Drained && it.op == Opcode.LIST && it.frames == 302 })
    }

    @Test
    fun lateClosedOfCancelledCloseNeverReachesNextList() = lateClosedIsDrained(Opcode.LIST) { it.list() }

    @Test
    fun lateClosedOfCancelledCloseNeverReachesNextOpen() = lateClosedIsDrained(Opcode.OPEN) { it.open("a.parquet") }

    private fun lateClosedIsDrained(nextOp: Int, next: suspend (TinyApi) -> Any) = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.LIST -> emit(Frame.File(10, "a.parquet"), Frame.ListEnd(1, 0, 0, 0))
                Opcode.OPEN -> emit(Frame.Opened(2, 10, 0, "a.parquet"))
            }
        }
        val s = session(driver)
        s.open()

        val first = TinyApi(s)
        val closing = launch { first.close(7) }
        runCurrent()
        closing.cancelAndJoin()

        // A second caller builds its own typed API on the same session.
        val second = TinyApi(s)
        val result = async { next(second) }
        runCurrent()
        assertEquals(listOf(Opcode.CLOSE, Opcode.ABORT), driver.sent, "next request waits for the drain")

        driver.emit(Frame.Closed(7), Frame.Aborted(0))
        runCurrent()
        when (nextOp) {
            Opcode.LIST -> assertEquals(listOf("a.parquet"), result.await())
            else -> assertEquals(Frame.Opened(2, 10, 0, "a.parquet"), result.await())
        }
        assertEquals(listOf(Opcode.CLOSE, Opcode.ABORT, nextOp), driver.sent)
        assertTrue(events.none { it is LinkEvent.ForeignDropped })
    }

    @Test
    fun stalledConsumerPastBudgetClosesWithOverflowAndGetsNoMoreFrames() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver, budget = 2_000)
        s.open()
        val gate = CompletableDeferred<Unit>()
        var delivered = 0
        val reply = async {
            runCatching {
                s.exchange<Unit>(RequestSpec.of(Request.List)) { _, _ ->
                    delivered++
                    gate.await()
                    null
                }
            }
        }
        runCurrent()
        driver.emit(Frame.File(1, "first.parquet"))
        runCurrent()
        assertEquals(1, delivered)
        // The consumer is stuck in its first frame while the peer keeps sending.
        repeat(40) { driver.emit(Frame.File(1, "x".repeat(90))) }
        runCurrent()

        val cause = s.closed.await()
        assertIs<DisconnectCause.ResponseOverflow>(cause)
        assertEquals(2_000, cause.budget)
        assertTrue(cause.queuedBytes > 2_000)
        assertEquals(1, driver.abortCount)

        gate.complete(Unit)
        runCurrent()
        val failure = reply.await().exceptionOrNull()
        assertIs<LinkClosedException>(failure)
        assertEquals(cause, failure.disconnectCause)
        assertEquals(1, delivered)
    }

    @Test
    fun drainPastFrameBoundClosesWithDrainExceededForList() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        val bound = DrainBound(maxFrames = 10)
        val list = launch { s.exchange<Unit>(RequestSpec.of(Request.List, bound)) { _, _ -> null } }
        runCurrent()
        list.cancelAndJoin()
        driver.emit(*files(50))
        runCurrent()
        val cause = s.closed.await()
        assertIs<DisconnectCause.DrainExceeded>(cause)
        assertEquals(Opcode.LIST, cause.op)
        assertEquals(11, cause.frames)
    }

    @Test
    fun drainPastTimeBoundClosesWithDrainExceededForList() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        val list = launch { s.collect(Request.List) }
        runCurrent()
        list.cancelAndJoin()
        advanceTimeBy(29.seconds)
        runCurrent()
        assertIs<LinkState.Ready>(s.state.value)
        advanceTimeBy(2.seconds)
        runCurrent()
        val cause = s.closed.await()
        assertIs<DisconnectCause.DrainExceeded>(cause)
        assertEquals(Opcode.LIST, cause.op)
        assertEquals(30.seconds, cause.elapsed)
    }

    @Test
    fun heartbeatDeadlineCountsReaderRxNotConsumerProgress() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver, heartbeat = Heartbeat(pingAfterIdle = 120.seconds, deadline = 180.seconds))
        s.open()
        val stalled = launch { s.exchange<Unit>(RequestSpec.of(Request.List)) { _, _ -> awaitCancellation() } }
        runCurrent()
        // The consumer never returns, the peer keeps sending.
        repeat(10) {
            driver.emit(Frame.File(1, "f.parquet"))
            advanceTimeBy(60.seconds)
            runCurrent()
        }
        assertIs<LinkState.Ready>(s.state.value)
        assertEquals(listOf(Opcode.LIST), driver.sent)

        // Peer goes quiet at t0. A ping goes out at t0 + 120 s, unanswered, then the deadline.
        val t0 = currentTime - 60_000
        advanceTimeBy(t0 + 179_000 - currentTime)
        runCurrent()
        assertIs<LinkState.Ready>(s.state.value)
        assertEquals(listOf(Opcode.LIST, Opcode.STATUS), driver.sent)
        advanceTimeBy(1_001)
        runCurrent()
        val cause = s.closed.await()
        assertIs<DisconnectCause.HeartbeatDeadline>(cause)
        assertEquals(180.seconds, cause.rxIdle)
        stalled.cancel()
    }

    @Test
    fun unframedRxActivityAlsoKeepsTheLinkAlive() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver, heartbeat = Heartbeat(pingAfterIdle = 120.seconds, deadline = 180.seconds))
        s.open()
        repeat(10) {
            advanceTimeBy(100.seconds)
            driver.sink.onRxActivity()
            runCurrent()
        }
        assertIs<LinkState.Ready>(s.state.value)
    }

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
    fun closeWakesWaiterAtOnceAndAWaitDoesNotPoll() = runTest {
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val driver = ScriptedLinkDriver()
        val s = LinkSession(driver, backgroundScope + counting, timeSource = testScheduler.timeSource)
        s.open()
        val spec = RequestSpec(Opcode.LIST, Request.List.encode(), firstFrameTimeout = 5.minutes)
        val waiter = async(counting) { runCatching { s.exchange<Unit>(spec) { _, _ -> null } } }
        runCurrent()
        assertEquals(listOf(Opcode.LIST), driver.sent)

        val before = counting.dispatches
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(before, counting.dispatches, "a waiting request must not wake up")

        val closedAt = currentTime
        s.close("test")
        runCurrent()
        assertTrue(waiter.isCompleted)
        assertEquals(closedAt, currentTime)
        assertIs<LinkClosedException>(waiter.await().exceptionOrNull())
        assertEquals(DisconnectCause.Deliberate("test"), s.closed.await())
    }

    @Test
    fun onceSinkDeliversOneCloseUnderRacingClosers() = runTest {
        withContext(Dispatchers.Default) {
            repeat(200) {
                val closes = AtomicInt(0)
                val frames = AtomicInt(0)
                val once = OnceSink(object : FrameSink {
                    override fun onFrame(raw: ByteArray) {
                        frames.incrementAndFetch()
                    }
                    override fun onPush(channel: PushChannel, json: ByteArray) = Unit
                    override fun onRxActivity() = Unit
                    override fun onClosed(cause: DisconnectCause) {
                        closes.incrementAndFetch()
                    }
                })
                val racers = List(8) { i ->
                    launch {
                        if (i % 2 == 0) {
                            once.onClosed(DisconnectCause.PeerClosed())
                        } else {
                            once.onClosed(DisconnectCause.LocalAbort())
                        }
                    }
                }
                racers.forEach { it.join() }
                once.onFrame(byteArrayOf(0x10))
                assertEquals(1, closes.load())
                assertEquals(0, frames.load())
            }
        }
    }

    @Test
    fun sessionClosesOnceUnderConcurrentAbortAndEof() = runTest {
        repeat(100) {
            val closedEvents = AtomicInt(0)
            val driver = ScriptedLinkDriver()
            val s = LinkSession(
                driver,
                backgroundScope,
                timeSource = testScheduler.timeSource,
                log = { if (it is LinkEvent.Closed) closedEvents.incrementAndFetch() },
            )
            s.open()
            withContext(Dispatchers.Default) {
                val a = launch { driver.abort(DisconnectCause.Deliberate("abort")) }
                val b = launch { driver.eof() }
                a.join()
                b.join()
            }
            val cause = s.closed.await()
            assertTrue(cause == DisconnectCause.Deliberate("abort") || cause == DisconnectCause.PeerClosed())
            assertEquals(1, closedEvents.load())
            assertEquals(LinkState.Closed(cause), s.state.value)
        }
    }

    @Test
    fun byeThenEofClosesWithByeAndLaterRequestsFailFast() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        val bye = Frame.Bye(reason = 1, generation = 7, err = -11, queued = 3, uptimeSeconds = 900)
        driver.emit(bye)
        runCurrent()

        val refused = assertFailsWith<LinkClosedException> { s.collect(Request.List) }
        assertEquals(DisconnectCause.PeerClosed(bye), refused.disconnectCause)
        assertEquals(emptyList(), driver.sent)

        driver.eof()
        assertEquals(DisconnectCause.PeerClosed(bye), s.closed.await())
    }

    @Test
    fun tokenRotatedByeClosesAsTokenRotatedAuth() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        driver.emit(Frame.Bye(reason = DisconnectReason.TOKEN_ROTATED.code, generation = 3, err = 0, queued = 0, uptimeSeconds = 60))
        runCurrent()

        val rotated = DisconnectCause.Auth(AuthKind.TOKEN_ROTATED)
        assertEquals(rotated, assertFailsWith<LinkClosedException> { s.collect(Request.List) }.disconnectCause)
        driver.eof()
        assertEquals(rotated, s.closed.await())
        assertFalse(s.closed.await().retryable, "a rotated token never retries LAN")
    }

    @Test
    fun byeReasonsWithATypedCauseKeepIt() {
        fun bye(reason: DisconnectReason) = Frame.Bye(reason = reason.code, generation = 1, err = 0, queued = 0, uptimeSeconds = 1)
        assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), peerClosedCause(bye(DisconnectReason.TOKEN_ROTATED)))
        assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), peerClosedCause(bye(DisconnectReason.AUTH_FAILED)))
        assertEquals(DisconnectCause.Busy, peerClosedCause(bye(DisconnectReason.SESSIONS_FULL)))
        assertEquals(DisconnectCause.PeerClosed(null), peerClosedCause(null))
        for (reason in DisconnectReason.entries - setOf(DisconnectReason.TOKEN_ROTATED, DisconnectReason.AUTH_FAILED, DisconnectReason.SESSIONS_FULL)) {
            val b = bye(reason)
            assertEquals(DisconnectCause.PeerClosed(b), peerClosedCause(b), reason.wireName)
        }
    }

    @Test
    fun byeWithoutEofClosesAfterGrace() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        val bye = Frame.Bye(reason = 2, generation = 1, err = 0, queued = 0, uptimeSeconds = 10)
        driver.emit(bye)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(DisconnectCause.PeerClosed(bye), s.closed.await())
        assertEquals(1, driver.abortCount)
    }

    @Test
    fun abortOnProto3PeerEndsAbandonedOwnerOnErrorAndAborted() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                // F-B: the running LIST stops with ERROR 16, then ABORTED names it.
                Opcode.ABORT -> emit(Frame.Error(Opcode.LIST, ProtocolError.ABORTED.code, ""), Frame.Aborted(Opcode.LIST))
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver)
        s.open()
        val list = launch { s.collect(Request.List) }
        runCurrent()
        driver.emit(*files(3))
        runCurrent()
        list.cancelAndJoin()
        runCurrent()

        assertEquals(Frame.Config(0, "{}"), TinyApi(s).getConfig())
        assertEquals(listOf(Opcode.LIST, Opcode.ABORT, Opcode.GET_CONFIG), driver.sent)
        assertTrue(events.any { it is LinkEvent.Drained && it.op == Opcode.LIST && it.frames == 2 })
    }

    @Test
    fun idleAbortedWithOpZeroIsAcceptedAndDropped() = runTest {
        val driver = ScriptedLinkDriver { op, _ -> if (op == Opcode.GET_CONFIG) emit(Frame.Config(0, "{}")) }
        val s = session(driver)
        s.open()
        driver.emit(Frame.Aborted(0))
        runCurrent()
        assertIs<LinkState.Ready>(s.state.value)
        assertTrue(events.none { it is LinkEvent.ForeignDropped })
        assertEquals(Frame.Config(0, "{}"), TinyApi(s).getConfig())
    }

    @Test
    fun explicitAbortWhileIdleReturnsAbortedZero() = runTest {
        val driver = ScriptedLinkDriver { op, _ -> if (op == Opcode.ABORT) emit(Frame.Aborted(0)) }
        val s = session(driver)
        s.open()
        assertEquals(listOf<Frame>(Frame.Aborted(0)), s.collect(Request.Abort))
    }

    @Test
    fun timedOutRequestKeepsLinkAndDrainsItsLateReply() = runTest {
        val driver = ScriptedLinkDriver { op, _ -> if (op == Opcode.GET_CONFIG) emit(Frame.Config(0, "{}")) }
        val s = session(driver)
        s.open()
        val failure = async { runCatching { s.collect(Request.SetTime(1_800_000_000)) } }
        advanceTimeBy(RequestSpec.DEFAULT_FIRST_FRAME + 1.seconds)
        runCurrent()
        val timeout = assertIs<LinkTimeoutException>(failure.await().exceptionOrNull())
        assertEquals(DisconnectCause.Timeout(TimeoutPhase.FIRST_FRAME, Opcode.SET_TIME), timeout.timeout)

        val config = async { TinyApi(s).getConfig() }
        runCurrent()
        driver.emit(Frame.TimeSet(1_800_000_000, 1, null), Frame.Aborted(0))
        runCurrent()
        assertEquals(Frame.Config(0, "{}"), config.await())
        assertIs<LinkState.Ready>(s.state.value)
    }

    @Test
    fun replyThatArrivesJustBeforeEofStillReachesItsOwner() = runTest {
        val bye = Frame.Bye(reason = 11, generation = 1, err = 0, queued = 0, uptimeSeconds = 60)
        val driver = ScriptedLinkDriver { op, _ ->
            if (op == Opcode.REBOOT) {
                emit(Frame.Rebooting(500), bye)
                eof()
            }
        }
        val s = session(driver)
        s.open()
        assertEquals(listOf<Frame>(Frame.Rebooting(500)), s.collect(Request.Reboot))
        assertEquals(DisconnectCause.PeerClosed(bye), s.closed.await())
    }

    @Test
    fun lateAbortErrorAfterAbortedNeverFailsTheNextRequestOfTheSameOp() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                // F-B order with ABORTED first, the ERROR 16 lands after the drain ended.
                Opcode.ABORT -> emit(Frame.Aborted(Opcode.LIST))
                Opcode.LIST -> if (sent.count { it == Opcode.LIST } == 2) {
                    emit(Frame.Error(Opcode.LIST, ProtocolError.ABORTED.code, ""), Frame.File(1, "a.parquet"), Frame.ListEnd(1, 0, 0, 0))
                }
            }
        }
        val s = session(driver)
        s.open()
        val list = launch { s.collect(Request.List) }
        runCurrent()
        driver.emit(*files(2))
        runCurrent()
        list.cancelAndJoin()
        runCurrent()

        assertEquals(listOf("a.parquet"), TinyApi(s).list())
        assertTrue(events.any { it is LinkEvent.ForeignDropped })
    }

    @Test
    fun writeThatGotNoReplyEndsItsDrainOnIdleAborted() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.ABORT -> emit(Frame.Aborted(0))
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver)
        s.open()
        val list = launch { s.collect(Request.List) }
        runCurrent()
        list.cancelAndJoin()
        runCurrent()

        assertEquals(Frame.Config(0, "{}"), TinyApi(s).getConfig())
        assertEquals(0L, currentTime)
        assertIs<LinkState.Ready>(s.state.value)
    }

    @Test
    fun failedWriteOfARequestWithoutReplyNeverStartsADrain() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.STATUS -> throw LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.SEND, op))
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver)
        s.open()
        assertFailsWith<LinkTimeoutException> { s.send(RequestSpec.of(Request.Status)) }

        assertEquals(Frame.Config(0, "{}"), TinyApi(s).getConfig())
        assertEquals(listOf(Opcode.STATUS, Opcode.GET_CONFIG), driver.sent)
        assertTrue(events.none { it is LinkEvent.Abandoned })
    }

    @Test
    fun openThatFailsOnBadInfoClosesTheDriver() = runTest {
        val aborts = AtomicInt(0)
        val driver = object : LinkDriver {
            override val kind = TransportKind.LAN

            override suspend fun open(sink: FrameSink): LinkInfo = throw IllegalArgumentException("bad info json")

            override suspend fun send(body: ByteArray, deadline: Duration) = Unit

            override fun abort(cause: DisconnectCause) {
                aborts.incrementAndFetch()
            }
        }
        val s = session(driver)
        assertFailsWith<IllegalArgumentException> { s.open() }
        assertEquals(DisconnectCause.FramingViolation("bad device info"), s.closed.await())
        assertEquals(1, aborts.load())
    }

    @Test
    fun earlyResultDrainsTheRestOfTheReply() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.LIST -> emit(*files(4), Frame.ListEnd(4, 0, 0, 0))
                Opcode.ABORT -> emit(Frame.Aborted(0))
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver)
        s.open()
        val first = s.exchange(RequestSpec.of(Request.List)) { frame, _ -> frame as? Frame.File }
        assertEquals("f0.parquet", first.name)
        assertEquals(Frame.Config(0, "{}"), TinyApi(s).getConfig())
    }

    @Test
    fun requestsQueueInOrderAndEachSeesOnlyItsReply() = runTest {
        val driver = ScriptedLinkDriver { op, _ ->
            when (op) {
                Opcode.LIST -> emit(Frame.File(1, "a.parquet"), Frame.ListEnd(1, 0, 0, 0))
                Opcode.GET_CONFIG -> emit(Frame.Config(0, "{}"))
            }
        }
        val s = session(driver)
        s.open()
        val a = async { TinyApi(s).list() }
        val b = async { TinyApi(s).getConfig() }
        assertEquals(listOf("a.parquet"), a.await())
        assertEquals(Frame.Config(0, "{}"), b.await())
        assertEquals(listOf(Opcode.LIST, Opcode.GET_CONFIG), driver.sent)
    }

    @Test
    fun truncatedKnownFrameIsFramingViolationAndUnknownTypeIsDropped() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        driver.sink.onFrame(byteArrayOf(0x6A, 1, 2))
        runCurrent()
        assertIs<LinkState.Ready>(s.state.value)
        assertTrue(events.any { it is LinkEvent.UnknownDropped && it.type == 0x6A })

        driver.sink.onFrame(byteArrayOf(0x11, 1, 2))
        runCurrent()
        assertIs<DisconnectCause.FramingViolation>(s.closed.await())
    }

    @Test
    fun blePushesUpdateStatusAndLive() = runTest {
        val driver = ScriptedLinkDriver(kind = TransportKind.BLE)
        val s = session(driver)
        s.open()
        assertNull(s.status.value)
        driver.sink.onPush(PushChannel.STATUS, "{\"a\":1}".encodeToByteArray())
        driver.sink.onPush(PushChannel.LIVE, "{\"b\":2}".encodeToByteArray())
        assertEquals("{\"a\":1}", s.status.value?.decodeToString())
        assertEquals("{\"b\":2}", s.live.value?.decodeToString())
    }

    @Test
    fun proto2AndMissingProtoAreRefusedWithUpdateFirmware() = runTest {
        for ((info, reported) in listOf(DeviceInfo(proto = 2) to 2, DeviceInfo() to null)) {
            val s = session(ScriptedLinkDriver(info = info))
            val refused = assertFailsWith<LinkClosedException> { s.open() }
            val expected = DisconnectCause.UpdateFirmware(reported, 3)
            assertEquals(expected, refused.disconnectCause)
            assertEquals(LinkState.Closed(expected), s.state.value)
            assertTrue(!refused.disconnectCause.retryable)
        }
        val ok = session(ScriptedLinkDriver(info = DeviceInfo(proto = 3)))
        assertEquals(3, ok.open().proto)
        assertIs<LinkState.Ready>(ok.state.value)
    }

    @Test
    fun retryableFollowsTheCause() {
        assertTrue(!DisconnectCause.Auth(AuthKind.TOKEN_REJECTED).retryable)
        assertTrue(!DisconnectCause.Deliberate("x").retryable)
        assertTrue(!DisconnectCause.FramingViolation("x").retryable)
        assertTrue(DisconnectCause.PeerReset.retryable)
        assertTrue(DisconnectCause.HeartbeatDeadline(1.seconds).retryable)
        assertTrue(DisconnectCause.ResponseOverflow(2, 1).retryable)
    }
}
