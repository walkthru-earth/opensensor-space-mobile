package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.NotAllowedOnLinkException
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The real [TcpTransport] against an in-JVM device that speaks the LAN envelope from
 * docs/ble-sync-protocol.md: unframed `"AQS1"` + token, framed `HELLO`, then u16-LE frames with
 * `STATUS`/`LIVE` pushes interleaved between response frames.
 */
class TcpTransportTest {
    private val token = ByteArray(32) { (it * 11 + 3).toByte() }
    private val info = """{"proto":3,"fw":"cores3-parquet-v5","station":"53315f5f-cb85-4d8d-b623-d56266084189","dev":"441bf6e26b40","max_read":16384}"""

    /** A one-client fake device: [onRequest] answers each request body with frames to send. */
    private class FakeLanDevice(
        private val token: ByteArray,
        private val hello: ByteArray?,
        private val afterResponses: () -> Unit = {},
        private val onRequest: (ByteArray) -> List<ByteArray>,
    ) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val exec = Executors.newSingleThreadExecutor()
        var receivedHandshake: ByteArray? = null

        fun start() {
            exec.submit {
                try {
                    server.accept().use { s -> serve(s) }
                } catch (_: Exception) {
                }
            }
        }

        private fun serve(s: Socket) {
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = BufferedOutputStream(s.getOutputStream())
            val hs = ByteArray(36).also { inp.readFully(it) }
            receivedHandshake = hs
            val ok = hs.copyOfRange(0, 4).contentEquals("AQS1".toByteArray()) && hs.copyOfRange(4, 36).contentEquals(token)
            if (!ok) {
                LanFraming.write(out, FrameCodec.encode(Frame.Error(0, ProtocolError.AUTH_FAILED.code, "")))
                return
            }
            if (hello == null) return // "anything else closes the socket silently"
            LanFraming.write(out, hello)
            while (true) {
                val req = LanFraming.read(inp, 512) ?: return
                for (f in onRequest(req)) LanFraming.write(out, f)
                afterResponses()
            }
        }

        override fun close() {
            runCatching { server.close() }
            exec.shutdownNow()
        }
    }

    private fun hello(payloadMax: Int = 1024) = FrameCodec.encode(Frame.Hello(3, payloadMax, info))

    @Test
    fun `keepalive started eagerly after handshake still sends STATUS`() = runBlocking {
        val ping = CountDownLatch(1)
        val launches = AtomicInteger()
        // Force the second child (keepalive) to execute before launch returns. The reader uses
        // its normal IO thread so a blocking socket read cannot stall the connect coroutine.
        val eager = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (launches.getAndIncrement() == 1) block.run() else Dispatchers.IO.dispatch(context, block)
            }
        }
        FakeLanDevice(token, hello()) { req ->
            if (req[0].toInt() == Opcode.STATUS) {
                ping.countDown()
                listOf(FrameCodec.encode(Frame.StatusPush("""{"up_s":43}""")))
            } else {
                emptyList()
            }
        }.use { device ->
            device.start()
            val transport = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), device.port, token, eager, TcpLiveness(pollMs = 10, idleMs = 20, deadMs = 5_000))
            try {
                withTimeout(5_000) { transport.connect() }
                assertTrue("an eager keepalive must remain active", ping.await(3, TimeUnit.SECONDS))
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `large listing drains bounded response queue without losing frames`() = runBlocking {
        val count = 257
        val sent = CountDownLatch(1)
        FakeLanDevice(token, hello(), afterResponses = { sent.countDown() }) { req ->
            if (req[0].toInt() == Opcode.LIST) {
                (0 until count).map { FrameCodec.encode(Frame.File(100, "file_$it.parquet")) } + FrameCodec.encode(Frame.ListEnd(count.toLong(), 0, 1, 1))
            } else {
                emptyList()
            }
        }.use { device ->
            device.start()
            val transport = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), device.port, token)
            try {
                withTimeout(5_000) { transport.connect() }
                transport.writeControl(byteArrayOf(Opcode.LIST.toByte()))
                // Delay consumption until the device has sent the entire burst. This exceeds
                // the queue capacity, exercising the suspended producer and TCP buffering.
                assertTrue(sent.await(3, TimeUnit.SECONDS))
                val frames = withTimeout(5_000) { (0..count).map { FrameCodec.decode(transport.responseFrames.receive()) } }
                val files = frames.dropLast(1).map { it as Frame.File }
                val end = frames.last() as Frame.ListEnd
                assertEquals(count.toLong(), end.count)
                assertEquals((0 until count).map { "file_$it.parquet" }, files.map { it.name })
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `disconnect releases a reader blocked by response backpressure`() = runBlocking {
        val sent = CountDownLatch(1)
        FakeLanDevice(token, hello(), afterResponses = { sent.countDown() }) {
            (0 until 257).map { FrameCodec.encode(Frame.File(100, "file_$it.parquet")) }
        }.use { device ->
            device.start()
            val transport = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), device.port, token)
            try {
                withTimeout(5_000) { transport.connect() }
                transport.writeControl(byteArrayOf(Opcode.LIST.toByte()))
                assertTrue(sent.await(3, TimeUnit.SECONDS))
                withTimeout(5_000) { transport.disconnect() }
                assertTrue(transport.connectionState.value is ConnectionState.Disconnected)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `handshake HELLO info and interleaved pushes are demultiplexed in order`() = runBlocking {
        val payload = ByteArray(1017) { it.toByte() }
        val device = FakeLanDevice(token, hello()) { req ->
            when (req[0].toInt()) {
                Opcode.LIST -> listOf(
                    FrameCodec.encode(Frame.File(100, "a.parquet")),
                    FrameCodec.encode(Frame.LivePush("""{"seq":1,"pms":4,"pm25":9}""")),
                    FrameCodec.encode(Frame.File(200, "b.parquet")),
                    FrameCodec.encode(Frame.StatusPush("""{"up_s":42}""")),
                    FrameCodec.encode(Frame.ListEnd(2, 0, 1, 1)),
                )
                Opcode.READ -> listOf(
                    FrameCodec.encode(Frame.Chunk(1, 0, payload)),
                    FrameCodec.encode(Frame.LivePush("""{"seq":2}""")),
                    FrameCodec.encode(Frame.Chunk(1, 1017, byteArrayOf(1, 2, 3))),
                    FrameCodec.encode(Frame.ReadEnd(1, 1020, 0)),
                )
                Opcode.GET_TOKEN -> listOf(FrameCodec.encode(Frame.Error(Opcode.GET_TOKEN, ProtocolError.NOT_ALLOWED_ON_LINK.code, "")))
                Opcode.STATUS -> listOf(FrameCodec.encode(Frame.StatusPush("""{"up_s":43}""")))
                else -> listOf(FrameCodec.encode(Frame.Error(req[0].toInt(), ProtocolError.UNKNOWN_OPCODE.code, "")))
            }
        }
        device.use {
            it.start()
            val t = TcpTransport("AA:BB:CC:DD:EE:FF", "AQ-6b40", InetAddress.getLoopbackAddress(), it.port, token)
            val session = DeviceSession(t)
            val liveSeen = async(Dispatchers.Default) { withTimeout(5_000) { session.live.first() } }

            withTimeout(5_000) { t.connect() }
            assertTrue(t.connectionState.value is ConnectionState.Ready)
            assertEquals(1024 + 3, t.mtu)
            assertEquals(1024, session.payloadMax)
            assertArrayEquals(LanFraming.handshake(token), it.receivedHandshake)
            assertEquals("53315f5f-cb85-4d8d-b623-d56266084189", session.readInfo().station)
            assertEquals(3, DeviceInfo.parse(t.hello!!.infoJson).proto)

            val (files, end) = withTimeout(5_000) { session.list() }
            assertEquals(listOf("a.parquet", "b.parquet"), files.map { f -> f.name })
            assertEquals(2L, end.count)
            val live: LiveSample = liveSeen.await()
            assertEquals(9.0, live.pm25)

            // readStatus returns the latest push without a request once one exists
            assertEquals(42L, withTimeout(5_000) { session.readStatus() }.upSeconds)

            val chunks = ArrayList<Frame.Chunk>()
            val readEnd = withTimeout(5_000) { session.read(1, 0, 16384) { c -> chunks += c } }
            assertEquals(2, chunks.size)
            assertEquals(0L, chunks[0].offset)
            assertArrayEquals(payload, chunks[0].payload)
            assertEquals(1017L, chunks[1].offset)
            assertEquals(1020L, readEnd.nextOffset)
            assertEquals(chunks.sumOf { c -> c.payload.size }.toLong(), readEnd.nextOffset)

            try {
                withTimeout(5_000) { session.getToken() }
                fail("GET_TOKEN over LAN must be ERROR 13")
            } catch (_: NotAllowedOnLinkException) {
            }

            t.disconnect()
            assertTrue(t.connectionState.value is ConnectionState.Disconnected)
            t.close()
        }
    }

    @Test
    fun `wrong token yields a typed AUTH_FAILED and a Failed state`() = runBlocking {
        val device = FakeLanDevice(token, hello()) { emptyList() }
        device.use {
            it.start()
            val wrong = ByteArray(32) { 0x42 }
            val t = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), it.port, wrong)
            try {
                withTimeout(5_000) { t.connect() }
                fail("expected LanConnectException")
            } catch (e: LanConnectException) {
                assertEquals(LanFailure.AUTH_FAILED, e.failure)
            }
            assertTrue(t.connectionState.value is ConnectionState.Failed)
            t.close()
        }
    }

    @Test
    fun `device closing without HELLO is reported as an auth failure not a hang`() = runBlocking {
        val device = FakeLanDevice(token, hello = null) { emptyList() }
        device.use {
            it.start()
            val t = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), it.port, token)
            try {
                withTimeout(5_000) { t.connect() }
                fail("expected LanConnectException")
            } catch (e: LanConnectException) {
                assertEquals(LanFailure.AUTH_FAILED, e.failure)
            }
            t.close()
        }
    }

    @Test
    fun `busy device is typed as BUSY`() = runBlocking {
        val busy = FrameCodec.encode(Frame.Error(0, ProtocolError.BUSY.code, ""))
        val device = FakeLanDevice(token, busy) { emptyList() }
        device.use {
            it.start()
            val t = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), it.port, token)
            try {
                withTimeout(5_000) { t.connect() }
                fail("expected LanConnectException")
            } catch (e: LanConnectException) {
                assertEquals(LanFailure.BUSY, e.failure)
            }
            t.close()
        }
    }

    @Test
    fun `nothing listening is UNREACHABLE`() = runBlocking {
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val t = TcpTransport("AA:BB:CC:DD:EE:FF", null, InetAddress.getLoopbackAddress(), free, token)
        try {
            withTimeout(10_000) { t.connect() }
            fail("expected LanConnectException")
        } catch (e: LanConnectException) {
            assertEquals(LanFailure.UNREACHABLE, e.failure)
        }
        t.close()
    }
}
