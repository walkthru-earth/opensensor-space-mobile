package space.opensensor.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.opensensor.data.ble.TransportException
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class LinkOweCloseTest {
    private val events = mutableListOf<LinkEvent>()

    private fun TestScope.session(driver: LinkDriver) = LinkSession(
        driver = driver,
        scope = backgroundScope,
        timeSource = testScheduler.timeSource,
        log = { events += it },
    )

    private val listReply: ScriptedLinkDriver.(Int, ByteArray) -> Unit = { op, _ ->
        if (op == Opcode.LIST) emit(Frame.File(10, "a.parquet"), Frame.ListEnd(1, 0, 0, 0))
    }

    @Test
    fun owedCloseFollowsTheDrainOfACancelledReadAndItsClosedIsConsumed() = runTest {
        val driver = ScriptedLinkDriver(respond = listReply)
        val s = session(driver)
        s.open()
        val read = launch { s.collect(Request.Read(7, 0, 4096)) }
        runCurrent()
        driver.emit(Frame.Chunk(7, 0, ByteArray(16)))
        runCurrent()

        val stoppedAt = currentTime
        read.cancelAndJoin()
        s.oweClose(7)
        // The cancelled caller never waits for the drain or the owed CLOSE.
        assertTrue(currentTime - stoppedAt < 100, "Stop took ${currentTime - stoppedAt} ms")
        runCurrent()
        assertEquals(listOf(Opcode.READ, Opcode.ABORT), driver.sent)

        val next = async { TinyApi(s).list() }
        runCurrent()
        assertEquals(listOf(Opcode.READ, Opcode.ABORT), driver.sent, "CLOSE waits for the READ drain")

        // The rest of the window, its READ_END, then the answer to ABORT.
        driver.emit(Frame.Chunk(7, 16, ByteArray(16)), Frame.ReadEnd(7, 32, 0), Frame.Aborted(Opcode.READ))
        runCurrent()
        assertEquals(listOf(Opcode.READ, Opcode.ABORT, Opcode.CLOSE), driver.sent, "LIST waits for CLOSED")
        assertTrue(events.any { it == LinkEvent.CloseSent(7) })
        assertFalse(next.isCompleted)

        driver.emit(Frame.Closed(7))
        runCurrent()
        assertEquals(listOf("a.parquet"), next.await())
        assertEquals(listOf(Opcode.READ, Opcode.ABORT, Opcode.CLOSE, Opcode.LIST), driver.sent)
        assertTrue(events.any { it is LinkEvent.Drained && it.op == Opcode.READ })
        assertTrue(events.any { it is LinkEvent.Drained && it.op == Opcode.CLOSE })
        assertTrue(events.none { it is LinkEvent.ForeignDropped }, "CLOSED was consumed by the owed CLOSE, not dropped")
        assertIs<LinkState.Ready>(s.state.value)
    }

    @Test
    fun owedCloseWhileIdleIsSentAtOnceAndHoldsTheNextRequestUntilClosed() = runTest {
        val driver = ScriptedLinkDriver(respond = listReply)
        val s = session(driver)
        s.open()
        s.oweClose(3)
        runCurrent()
        assertEquals(listOf(Opcode.CLOSE), driver.sent)
        assertTrue(events.any { it == LinkEvent.CloseSent(3) })

        val next = async { TinyApi(s).list() }
        runCurrent()
        assertEquals(listOf(Opcode.CLOSE), driver.sent)

        driver.emit(Frame.Closed(3))
        runCurrent()
        assertEquals(listOf("a.parquet"), next.await())
        assertEquals(listOf(Opcode.CLOSE, Opcode.LIST), driver.sent)
    }

    @Test
    fun owedCloseOnAClosedLinkDoesNothing() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        s.close("test")
        runCurrent()
        s.oweClose(3)
        runCurrent()
        assertTrue(driver.sent.isEmpty())
        assertTrue(events.none { it is LinkEvent.CloseSent })
        assertEquals(LinkState.Closed(DisconnectCause.Deliberate("test")), s.state.value)
    }

    @Test
    fun aLateWriteFailureOfAnEarlierOwedCloseDoesNotEndTheNextOne() = runTest {
        val peer = ScriptedLinkDriver(respond = listReply)
        val driver = FailFirstCloseLate(peer)
        val s = session(driver)
        s.open()
        s.oweClose(3)
        s.oweClose(4)
        runCurrent()
        // CLOSED for 3 arrives while its write is still in flight, so the owed CLOSE for 4 starts.
        peer.emit(Frame.Closed(3))
        runCurrent()
        val next = async { TinyApi(s).list() }
        runCurrent()

        driver.failWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(Opcode.CLOSE), peer.sent, "LIST waits for CLOSED 4")
        assertFalse(next.isCompleted)

        peer.emit(Frame.Closed(4))
        runCurrent()
        assertEquals(listOf("a.parquet"), next.await())
        assertEquals(listOf(Opcode.CLOSE, Opcode.LIST), peer.sent)
        assertTrue(events.none { it is LinkEvent.ForeignDropped }, "CLOSED 4 was consumed by its own drain")
    }

    @Test
    fun aCloseCancelledAfterItsWriteIsDrainedAndNeverSentTwice() = runTest {
        val driver = ScriptedLinkDriver(respond = listReply)
        val s = session(driver)
        s.open()
        val close = launch { s.closeOwing(5) }
        runCurrent()
        close.cancelAndJoin()
        runCurrent()
        val next = async { TinyApi(s).list() }
        runCurrent()

        driver.emit(Frame.Closed(5), Frame.Aborted(Opcode.CLOSE))
        runCurrent()
        assertEquals(listOf("a.parquet"), next.await())
        assertEquals(1, driver.sent.count { it == Opcode.CLOSE }, "the device sees one CLOSE")
        assertTrue(events.none { it is LinkEvent.CloseSent })
    }

    @Test
    fun aCloseCancelledBeforeItsWriteIsOwedOnce() = runTest {
        val driver = ScriptedLinkDriver()
        val s = session(driver)
        s.open()
        val list = async { TinyApi(s).list() }
        runCurrent()
        val close = launch { s.closeOwing(5) }
        runCurrent()
        close.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(Opcode.LIST), driver.sent)

        driver.emit(Frame.File(10, "a.parquet"), Frame.ListEnd(1, 0, 0, 0))
        runCurrent()
        assertEquals(listOf("a.parquet"), list.await())
        assertEquals(listOf(Opcode.LIST, Opcode.CLOSE), driver.sent)
        assertTrue(events.any { it == LinkEvent.CloseSent(5) })
    }

    @Test
    fun awaitQuietWaitsForTheDrainAndTheOwedClose() = runTest {
        val driver = ScriptedLinkDriver(respond = listReply)
        val s = session(driver)
        s.open()
        s.awaitQuiet()
        val read = launch { s.collect(Request.Read(7, 0, 4096)) }
        runCurrent()
        read.cancelAndJoin()
        s.oweClose(7)
        val quiet = async { s.awaitQuiet() }
        runCurrent()
        assertFalse(quiet.isCompleted, "the READ drain is still open")

        driver.emit(Frame.ReadEnd(7, 0, 0), Frame.Aborted(Opcode.READ))
        runCurrent()
        assertEquals(listOf(Opcode.READ, Opcode.ABORT, Opcode.CLOSE), driver.sent)
        assertFalse(quiet.isCompleted, "the owed CLOSE waits for CLOSED")

        driver.emit(Frame.Closed(7))
        runCurrent()
        assertTrue(quiet.isCompleted)
        quiet.await()
    }

    @Test
    fun awaitQuietOnAClosedLinkThrows() = runTest {
        val s = session(ScriptedLinkDriver())
        s.open()
        s.close("test")
        runCurrent()
        assertFailsWith<LinkClosedException> { s.awaitQuiet() }
    }

    private suspend fun LinkSession.closeOwing(handle: Int): Frame =
        exchange(RequestSpec.of(Request.Close(handle)), oweCloseIfUnsent = handle) { frame, terminal -> if (terminal) frame else null }

    /** Holds the first `CLOSE` write until [failWrite], then fails it. */
    private class FailFirstCloseLate(private val peer: ScriptedLinkDriver) : LinkDriver by peer {
        val failWrite = CompletableDeferred<Unit>()
        private var held = false

        override suspend fun send(body: ByteArray, deadline: Duration) {
            if (!held && (body[0].toInt() and 0xFF) == Opcode.CLOSE) {
                held = true
                failWrite.await()
                throw TransportException("write failed")
            }
            peer.send(body, deadline)
        }
    }
}
