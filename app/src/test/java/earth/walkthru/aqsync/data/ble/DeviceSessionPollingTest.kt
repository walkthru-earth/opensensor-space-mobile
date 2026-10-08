package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSessionPollingTest {
    private val opened = Frame.Opened(1, 12, 0, "legacy-parquet/boundary.parquet")

    @Test
    fun `response arriving at polling boundary is delivered even before receiver resumes`() = runTest {
        val transport = ResponseTransport()
        // Schedule the sender's timer first. At 250 ms send extracts a waiting receiver, but its
        // continuation queues behind the poll timer. withTimeoutOrNull used to lose OPENED here.
        launch { delay(250); transport.responseFrames.send(FrameCodec.encode(opened)) }
        runCurrent()
        val request = async { DeviceSession(transport).open(opened.name) }
        try {
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            assertTrue("polling must not discard an already-selected response", request.isCompleted)
            assertEquals(opened, request.await())
        } finally {
            request.cancelAndJoin()
        }
    }

    @Test
    fun `poll timeout wins without consuming the response arriving on the next tick`() = runTest {
        val transport = ResponseTransport()
        launch { delay(251); transport.responseFrames.send(FrameCodec.encode(opened)) }
        runCurrent()
        val request = async { DeviceSession(transport).open(opened.name) }
        try {
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            assertFalse(request.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(request.isCompleted)
            assertEquals(opened, request.await())
        } finally {
            request.cancelAndJoin()
        }
    }

    @Test
    fun `raw command quiet boundary preserves the selected response`() = runTest {
        val transport = ResponseTransport()
        launch { delay(250); transport.responseFrames.send(FrameCodec.encode(opened)) }
        runCurrent()
        val request = async { DeviceSession(transport).rawCommand(byteArrayOf(Opcode.OPEN.toByte()), quietMs = 250) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(opened), request.await().map { it.second })
    }

    @Test
    fun `quiet timeout leaves a later response in the transport channel`() = runTest {
        val transport = ResponseTransport()
        val raw = FrameCodec.encode(opened)
        launch { delay(251); transport.responseFrames.send(raw) }
        runCurrent()
        val request = async { DeviceSession(transport).rawCommand(byteArrayOf(Opcode.OPEN.toByte()), quietMs = 250) }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertTrue(request.await().isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertArrayEquals(raw, transport.responseFrames.tryReceive().getOrThrow())
    }

    @Test
    fun `lost link is detected at the same polling interval`() = runTest {
        val transport = ResponseTransport()
        val request = async { runCatching { DeviceSession(transport).open(opened.name) } }
        runCurrent()
        transport.connectionState.value = ConnectionState.Disconnected
        advanceTimeBy(250)
        runCurrent()
        val failure = request.await().exceptionOrNull()
        assertTrue(failure is TransportException)
        assertEquals("link lost while waiting for a response", failure!!.message)
    }

    @Test
    fun `cancelling a request frees the receiver and the next request drains its late terminal`() = runTest {
        val transport = ResponseTransport()
        val stale = ArrayList<String>()
        val session = DeviceSession(transport).also { it.staleFrameLogger = stale::add }
        val request = async { session.open(opened.name) }
        runCurrent()
        request.cancelAndJoin()
        transport.responseFrames.send(FrameCodec.encode(opened))
        val next = async { session.list() }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.ListEnd(0, 1, 1024, 0)))
        runCurrent()
        assertTrue(next.await().first.isEmpty())
        assertEquals(1, stale.size)
        assertTrue(stale.single().contains("terminal frame seen"))
    }

    @Test
    fun `stale drain quiet boundary consumes the old terminal before the new request`() = runTest {
        val transport = ResponseTransport()
        val stale = ArrayList<String>()
        val session = DeviceSession(transport).also { it.staleFrameLogger = stale::add }
        val request = async { session.open(opened.name) }
        runCurrent()
        request.cancelAndJoin()
        launch { delay(2_000); transport.responseFrames.send(FrameCodec.encode(opened)) }
        runCurrent()
        val next = async { session.list() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(stale.single().contains("terminal frame seen"))
        transport.responseFrames.send(FrameCodec.encode(Frame.ListEnd(0, 1, 1024, 0)))
        runCurrent()
        assertTrue(next.await().first.isEmpty())
    }

    @Test
    fun `GET_CONFIG reassembles ordered BLE chunks before parsing JSON`() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val json = """{"sensor":{"vendor":"Plantower","model":"PMS5003T","serial":"PMS5003T-202604081332","batch_candidate":1}}"""
        val bytes = json.toByteArray()
        val request = async { session.getConfig() }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 0, bytes.copyOfRange(0, 40))))
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 40, bytes.copyOfRange(40, bytes.size))))
        runCurrent()
        assertEquals("PMS5003T-202604081332", request.await().config.sensor?.serial)
    }

    @Test
    fun `GET_CONFIG rejects missing chunk instead of accepting partial JSON`() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val request = async { runCatching { session.getConfig() } }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, 10, 0, byteArrayOf(1, 2))))
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, 10, 3, byteArrayOf(3, 4))))
        runCurrent()
        assertTrue(request.await().exceptionOrNull() is ProtocolViolationException)
    }

    private class ResponseTransport : AqTransport {
        override val address = "test-poll-boundary"
        override val displayName = "Test device"
        override val kind = TransportKind.FAKE
        override val endpoint = address
        override val mtu = 517
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))
        override val responseFrames = Channel<ByteArray>(Channel.UNLIMITED)
        override val statusNotifications: Flow<ByteArray> = emptyFlow()
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override suspend fun connect() {}
        override suspend fun disconnect() { connectionState.value = ConnectionState.Disconnected }
        override suspend fun readInfo(): ByteArray = error("unused")
        override suspend fun readStatus(): ByteArray = error("unused")
        override suspend fun readLive(): ByteArray = error("unused")
        override suspend fun writeControl(request: ByteArray) {}
        override fun close() { responseFrames.close() }
    }
}
