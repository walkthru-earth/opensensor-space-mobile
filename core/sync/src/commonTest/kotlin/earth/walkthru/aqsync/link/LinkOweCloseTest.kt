package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.Request
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
}
