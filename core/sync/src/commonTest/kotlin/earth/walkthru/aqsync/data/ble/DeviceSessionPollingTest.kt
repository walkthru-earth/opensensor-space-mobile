package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.Request
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSessionPollingTest {
    private val opened = Frame.Opened(1, 12, 0, "legacy-parquet/boundary.parquet")

    @Test
    fun incompleteListFailsAndItsConsumedTerminalDoesNotDelayTheNextRequest() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val listing = async { runCatching { session.list() } }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.File(12, opened.name)))
        transport.responseFrames.send(FrameCodec.encode(Frame.ListEnd(2, 0, 1024, 0)))
        runCurrent()
        assertTrue(listing.await().exceptionOrNull() is ProtocolViolationException)

        val next = async { session.list() }
        runCurrent()
        assertEquals(2, transport.writes.size, "a consumed LIST_END must not trigger stale-drain waiting")
        transport.responseFrames.send(FrameCodec.encode(Frame.ListEnd(0, 0, 1024, 0)))
        runCurrent()
        assertTrue(next.await().first.isEmpty())
    }

    @Test
    fun tooManyFileFramesFailInsteadOfAcceptingAnUnreliableListing() = runTest {
        val transport = ResponseTransport()
        val listing = async { runCatching { DeviceSession(transport).list() } }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.File(12, opened.name)))
        transport.responseFrames.send(FrameCodec.encode(Frame.ListEnd(0, 0, 1024, 0)))
        runCurrent()
        assertTrue(listing.await().exceptionOrNull() is ProtocolViolationException)
    }

    @Test
    fun responseArrivingAtPollingBoundaryIsDeliveredEvenBeforeReceiverResumes() = runTest {
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
            assertTrue(request.isCompleted, "polling must not discard an already-selected response")
            assertEquals(opened, request.await())
        } finally {
            request.cancelAndJoin()
        }
    }

    @Test
    fun pollTimeoutWinsWithoutConsumingTheResponseArrivingOnTheNextTick() = runTest {
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
    fun rawCommandQuietBoundaryPreservesTheSelectedResponse() = runTest {
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
    fun quietTimeoutLeavesALaterResponseInTheTransportChannel() = runTest {
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
        assertContentEquals(raw, transport.responseFrames.tryReceive().getOrThrow())
    }

    @Test
    fun lostLinkIsDetectedAtTheSamePollingInterval() = runTest {
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
    fun cancellingARequestFreesTheReceiverAndTheNextRequestDrainsItsLateTerminal() = runTest {
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
    fun staleDrainQuietBoundaryConsumesTheOldTerminalBeforeTheNewRequest() = runTest {
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
    fun getConfigReassemblesOrderedBleChunksBeforeParsingJson() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val json = """{"sensor":{"vendor":"Plantower","model":"PMS5003T","serial":"PMS5003T-202604081332","batch_candidate":1}}"""
        val bytes = json.encodeToByteArray()
        val request = async { session.getConfig() }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 0, bytes.copyOfRange(0, 40))))
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 40, bytes.copyOfRange(40, bytes.size))))
        runCurrent()
        assertEquals("PMS5003T-202604081332", request.await().config.sensor?.serial)
    }

    @Test
    fun getConfigRejectsMissingChunkInsteadOfAcceptingPartialJson() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val request = async { runCatching { session.getConfig() } }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, 10, 0, byteArrayOf(1, 2))))
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, 10, 3, byteArrayOf(3, 4))))
        runCurrent()
        assertTrue(request.await().exceptionOrNull() is ProtocolViolationException)
    }

    @Test
    fun automaticClockUsesAcknowledgedPrecisionAndDoesNotInventLegacyFractions() = runTest {
        val transport = ResponseTransport()
        val request = async { DeviceSession(transport).synchronizeClock { 1_789_581_931_987L } }
        runCurrent()
        assertContentEquals(Request.SetTime(1_789_581_931L, 987_000).encode(), transport.writes.single())
        transport.responseFrames.send(FrameCodec.encode(Frame.TimeSet(1_789_581_931L, 500)))
        assertEquals(1_789_581_931_000_000_000L, request.await().epochNanos)
    }

    @Test
    fun automaticClockRetriesOnlyMalformedExtensionWithFreshLegacySeconds() = runTest {
        val transport = ResponseTransport()
        var now = 1_789_581_931_987L
        val request = async { DeviceSession(transport).synchronizeClock { now } }
        runCurrent()
        now += 1000
        transport.responseFrames.send(FrameCodec.encode(Frame.Error(Opcode.SET_TIME, ProtocolError.MALFORMED_REQUEST.code, "legacy length")))
        runCurrent()
        assertContentEquals(Request.SetTime(1_789_581_932L).encode(), transport.writes.last())
        transport.responseFrames.send(FrameCodec.encode(Frame.TimeSet(1_789_581_932L, 501)))
        assertEquals(501L, request.await().monotonicMicros)
        assertEquals(2, transport.writes.size)
    }

    @Test
    fun aRefusedClockIsNotRetriedAsALegacyRequest() = runTest {
        val transport = ResponseTransport()
        val request = async { runCatching { DeviceSession(transport).synchronizeClock { 1_789_581_931_987L } } }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.Error(Opcode.SET_TIME, 9, "refused")))
        assertTrue(request.await().exceptionOrNull() is DeviceErrorException)
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun maintenanceReadsFreshStatusAndRetainsARecentExternalClock() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        for (source in listOf(1, 3)) {
            val request = async { session.synchronizeClockIfNeeded { 1_000_000L } }
            runCurrent()
            assertContentEquals(Request.Status.encode(), transport.writes.last())
            transport.statusNotifications.emit("{\"utc\":1,\"clk\":$source,\"utc_ms\":1000000,\"clk_age_s\":60}".encodeToByteArray())
            assertEquals(null, request.await())
        }
        assertEquals(2, transport.writes.size)
    }

    @Test
    fun maintenanceAnchorsMissingRtcAndOldExternalClocks() = runTest {
        for (json in listOf("{\"utc\":0}", "{\"utc\":1,\"clk\":2}", "{\"utc\":1,\"clk\":1,\"clk_age_s\":21600}")) {
            val transport = ResponseTransport()
            val request = async { DeviceSession(transport).synchronizeClockIfNeeded { 1_000_000L } }
            runCurrent()
            transport.statusNotifications.emit(json.encodeToByteArray())
            runCurrent()
            assertContentEquals(Request.SetTime(1000L, 0).encode(), transport.writes.last())
            val ack = Frame.TimeSet(1000L, 501L, 0)
            transport.responseFrames.send(FrameCodec.encode(ack))
            assertEquals(ack, request.await())
        }
    }

    @Test
    fun maintenanceFallsBackForFirmwareWithoutStatusNotifications() = runTest {
        val transport = ResponseTransport()
        val request = async { DeviceSession(transport).synchronizeClockIfNeeded { 1_000_000L } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertContentEquals(Request.SetTime(1000L, 0).encode(), transport.writes.last())
        transport.responseFrames.send(FrameCodec.encode(Frame.TimeSet(1000L, 501L, 0)))
        assertEquals(501L, request.await()?.monotonicMicros)
    }

    @Test
    fun legacyStatusReadFallbackRetainsAnAlreadyUsableUtcClock() = runTest {
        val transport = ResponseTransport("{\"utc\":1}".encodeToByteArray())
        val request = async { DeviceSession(transport).synchronizeClockIfNeeded { 1_000_000L } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(null, request.await())
        assertEquals(1, transport.writes.size)
        assertContentEquals(Request.Status.encode(), transport.writes.single())
    }

    @Test
    fun aCachedFallbackCannotBeMistakenForClockDrift() = runTest {
        val transport = ResponseTransport("{\"utc\":1,\"clk\":1,\"utc_ms\":985000,\"clk_age_s\":60}".encodeToByteArray())
        val request = async { DeviceSession(transport).synchronizeClockIfNeeded { 1_000_000L } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(null, request.await())
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun maintenanceCancellationNeverSetsTime() = runTest {
        val transport = ResponseTransport()
        val request = async { DeviceSession(transport).synchronizeClockIfNeeded { 1_000_000L } }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(1, transport.writes.size)
        assertContentEquals(Request.Status.encode(), transport.writes.single())
    }

    private class ResponseTransport(private val statusSnapshot: ByteArray? = null) : AqTransport {
        override val address = "test-poll-boundary"
        override val displayName = "Test device"
        override val kind = TransportKind.FAKE
        override val endpoint = address
        override val mtu = 517
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))
        override val responseFrames = Channel<ByteArray>(Channel.UNLIMITED)
        override val statusNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override suspend fun connect() {}
        override suspend fun disconnect() { connectionState.value = ConnectionState.Disconnected }
        override suspend fun readInfo(): ByteArray = error("unused")
        override suspend fun readStatus(): ByteArray = statusSnapshot ?: error("unused")
        override suspend fun readLive(): ByteArray = error("unused")
        val writes = mutableListOf<ByteArray>()
        override suspend fun writeControl(request: ByteArray) { writes += request }
        override fun close() { responseFrames.close() }
    }

    @Test
    fun locationUsesTheExistingConfigChunkEnvelopeAndLegacyIsUnsupported() = runTest {
        val transport = ResponseTransport()
        val session = DeviceSession(transport)
        val json = """{"location":{"cell":"85283083fffffff","resolution":5,"country":"US"}}"""
        val bytes = json.encodeToByteArray()
        val request = async { session.getLocation() }
        runCurrent()
        assertContentEquals(Request.GetLocation.encode(), transport.writes.single())
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 0, bytes.copyOfRange(0, 40))))
        transport.responseFrames.send(FrameCodec.encode(Frame.ConfigChunk(0, bytes.size, 40, bytes.copyOfRange(40, bytes.size))))
        runCurrent()
        assertEquals("85283083fffffff", request.await()?.cell)
        val legacy = async { session.getLocation() }
        runCurrent()
        transport.responseFrames.send(FrameCodec.encode(Frame.Config(0, "{}")))
        runCurrent()
        assertEquals(null, legacy.await())
    }
}
