package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.NotAllowedOnLinkException
import earth.walkthru.aqsync.data.openableSession
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.Heartbeat
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkLog
import earth.walkthru.aqsync.link.LinkSession
import earth.walkthru.aqsync.link.LinkState
import earth.walkthru.aqsync.link.LinkTimeoutException
import earth.walkthru.aqsync.link.PushChannel
import earth.walkthru.aqsync.link.TimeoutPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The real [TcpLinkDriver] against an in-JVM device that speaks the LAN envelope from
 * `ble-sync-protocol.md`, unframed `"AQS1"` + token, framed `HELLO`, then u16 LE frames with
 * `STATUS` and `LIVE` pushes interleaved between response frames.
 */
class TcpLinkDriverTest {
    private val token = ByteArray(32) { (it * 11 + 3).toByte() }
    private val station = "00000000-0000-4000-8000-000000000001"
    private val info = """{"proto":3,"fw":"cores3-parquet-v5","station":"$station","dev":"000000000001","max_read":16384}"""

    /** A one-client fake device. [onRequest] answers each request body with frames to send. */
    private class FakeLanDevice(
        private val token: ByteArray,
        private val hello: ByteArray?,
        private val afterHello: (Socket) -> Unit = {},
        private val onRequest: (ByteArray) -> List<ByteArray>,
    ) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val exec = Executors.newSingleThreadExecutor()

        @Volatile
        var receivedHandshake: ByteArray? = null

        fun start() = apply {
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
            afterHello(s)
            while (true) {
                val req = LanFraming.read(inp, 512) ?: return
                synchronized(out) { for (f in onRequest(req)) out.write(LanFraming.encode(f)); out.flush() }
            }
        }

        override fun close() {
            runCatching { server.close() }
            exec.shutdownNow()
        }
    }

    /** Records what a driver reports when used without a session. */
    private class RecordingSink : FrameSink {
        val closed = CompletableDeferred<DisconnectCause>()
        val pushes = ArrayList<String>()
        override fun onFrame(raw: ByteArray) = Unit
        override fun onPush(channel: PushChannel, json: ByteArray) {
            synchronized(pushes) { pushes += json.decodeToString() }
        }
        override fun onRxActivity() = Unit
        override fun onClosed(cause: DisconnectCause) {
            closed.complete(cause)
        }
    }

    private fun hello(payloadMax: Int = 1024) = FrameCodec.encode(Frame.Hello(3, payloadMax, info))

    private fun driver(port: Int, key: ByteArray = token, socketFactory: () -> Socket = ::Socket) =
        TcpLinkDriver("AA:BB:CC:DD:EE:FF", "AQ", InetAddress.getLoopbackAddress(), port, key, Dispatchers.IO, socketFactory)

    private val statusAnswer: (ByteArray) -> List<ByteArray> = { req ->
        if (req[0].toInt() == Opcode.STATUS) listOf(FrameCodec.encode(Frame.StatusPush("""{"up_s":43}"""))) else emptyList()
    }

    @Test
    fun `HELLO info and interleaved pushes are demultiplexed in order`() = runBlocking {
        val payload = ByteArray(1017) { it.toByte() }
        FakeLanDevice(token, hello()) { req ->
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
                else -> listOf(FrameCodec.encode(Frame.Error(req[0].toInt(), ProtocolError.UNKNOWN_OPCODE.code, "")))
            }
        }.use { device ->
            device.start()
            val t = driver(device.port)
            val session = t.openableSession(this, LinkLog.NONE)
            val liveSeen = async(Dispatchers.Default) { withTimeout(5_000) { session.live.first() } }

            val opened = withTimeout(5_000) { session.link.open() }
            assertTrue(t.connectionState.value is ConnectionState.Ready)
            assertEquals(1024 + 3, t.mtu)
            assertEquals(1024, session.payloadMax)
            assertArrayEquals(LanFraming.handshake(token), device.receivedHandshake)
            assertEquals(station, opened.info.station)
            assertEquals(3, opened.proto)

            val (files, end) = withTimeout(5_000) { session.list() }
            assertEquals(listOf("a.parquet", "b.parquet"), files.map { f -> f.name })
            assertEquals(2L, end.count)
            val live: LiveSample = liveSeen.await()
            assertEquals(9.0, live.pm25)
            assertEquals(42L, withTimeout(5_000) { session.readStatus() }.upSeconds)

            val chunks = ArrayList<Frame.Chunk>()
            val readEnd = withTimeout(5_000) { session.read(1, 0, 16384) { c -> chunks += c } }
            assertEquals(listOf(0L, 1017L), chunks.map { it.offset })
            assertArrayEquals(payload, chunks[0].payload)
            assertEquals(1020L, readEnd.nextOffset)

            try {
                withTimeout(5_000) { session.getToken() }
                fail("GET_TOKEN over LAN must be ERROR 13")
            } catch (_: NotAllowedOnLinkException) {
            }

            session.link.close("done")
            assertTrue(t.connectionState.value is ConnectionState.Disconnected)
        }
    }

    @Test
    fun `a stalled consumer keeps the link while pings are answered`() = runBlocking {
        val count = 257
        val pings = CountDownLatch(2)
        FakeLanDevice(token, hello()) { req ->
            when (req[0].toInt()) {
                Opcode.LIST -> (0 until count).map { FrameCodec.encode(Frame.File(100, "file_$it.parquet")) } +
                    FrameCodec.encode(Frame.ListEnd(count.toLong(), 0, 1, 1))
                Opcode.STATUS -> {
                    pings.countDown()
                    listOf(FrameCodec.encode(Frame.StatusPush("""{"up_s":${pings.count}}""")))
                }
                else -> emptyList()
            }
        }.use { device ->
            device.start()
            val t = driver(device.port)
            // The LAN heartbeat scaled down: ping after 200 ms idle, dead after 1 s without a byte.
            val link = LinkSession(t, this, heartbeat = Heartbeat(200.milliseconds, 1.seconds), openTimeout = 5.seconds)
            withTimeout(5_000) { link.open() }
            t.writeControl(byteArrayOf(Opcode.LIST.toByte()))
            // Nobody reads the 258 queued frames for three deadlines. Only received bytes count.
            // The heartbeat runs on this thread, so wait without blocking it.
            withTimeout(5_000) { while (pings.count > 0) delay(20) }
            delay(3_000)
            assertTrue(link.state.value is LinkState.Ready)
            assertTrue(t.connectionState.value is ConnectionState.Ready)
            assertEquals("""{"up_s":0}""", withTimeout(5_000) { link.status.filterNotNull().first { it.decodeToString() == """{"up_s":0}""" } }.decodeToString())
            link.close("done")
        }
    }

    @Test
    fun `a peer that stops reading ends the write as Timeout SEND`() = runBlocking {
        val server = ServerSocket().apply {
            receiveBufferSize = 4096
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
        }
        val exec = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        exec.submit {
            runCatching {
                server.accept().use { s ->
                    DataInputStream(s.getInputStream()).readFully(ByteArray(36))
                    LanFraming.write(s.getOutputStream(), hello())
                    release.await(30, TimeUnit.SECONDS) // never reads again
                }
            }
        }
        try {
            val sink = RecordingSink()
            val t = driver(server.localPort) { Socket().apply { sendBufferSize = 4096 } }
            withTimeout(5_000) { t.open(sink) }
            val body = ByteArray(500) { Opcode.SET_TIME.toByte() }
            val e = try {
                withTimeout(30_000) { repeat(100_000) { t.send(body, 300.milliseconds) } }
                null
            } catch (e: LinkTimeoutException) {
                e
            }
            val expected = DisconnectCause.Timeout(TimeoutPhase.SEND, Opcode.SET_TIME)
            assertEquals(expected, e?.timeout)
            assertEquals(expected, withTimeout(1_000) { sink.closed.await() })
        } finally {
            release.countDown()
            server.close()
            exec.shutdownNow()
        }
    }

    @Test
    fun `two connections never see each other's frames`() = runBlocking {
        fun device(up: Int) = FakeLanDevice(token, hello()) { req ->
            when (req[0].toInt()) {
                Opcode.LIST -> listOf(FrameCodec.encode(Frame.File(100, "dev$up.parquet")), FrameCodec.encode(Frame.ListEnd(1, 0, 1, 1)))
                Opcode.STATUS -> listOf(FrameCodec.encode(Frame.StatusPush("""{"up_s":$up}""")))
                else -> emptyList()
            }
        }
        device(1).use { a ->
            device(2).use { b ->
                a.start()
                b.start()
                val sa = driver(a.port).openableSession(this, LinkLog.NONE)
                val sb = driver(b.port).openableSession(this, LinkLog.NONE)
                withTimeout(5_000) { sa.link.open() }
                withTimeout(5_000) { sb.link.open() }
                val la = async { sa.list() }
                val lb = async { sb.list() }
                assertEquals(listOf("dev1.parquet"), withTimeout(5_000) { la.await() }.first.map { it.name })
                assertEquals(listOf("dev2.parquet"), withTimeout(5_000) { lb.await() }.first.map { it.name })
                assertEquals(1L, withTimeout(5_000) { sa.readStatus() }.upSeconds)
                assertEquals(2L, withTimeout(5_000) { sb.readStatus() }.upSeconds)
                sa.link.close("done")
                sb.link.close("done")
            }
        }
    }

    @Test
    fun `peer closing its side is PeerClosed`() = runBlocking {
        FakeLanDevice(token, hello(), afterHello = { it.shutdownOutput() }) { emptyList() }.use { device ->
            device.start()
            val sink = RecordingSink()
            val t = driver(device.port)
            withTimeout(5_000) { t.open(sink) }
            assertEquals(DisconnectCause.PeerClosed(), withTimeout(5_000) { sink.closed.await() })
            assertTrue(t.connectionState.value is ConnectionState.Disconnected)
        }
    }

    @Test
    fun `an OS connection abort is LocalAbort with ECONNABORTED`() = runBlocking {
        val armed = CountDownLatch(1)
        FakeLanDevice(token, hello(), onRequest = statusAnswer).use { device ->
            device.start()
            val sink = RecordingSink()
            val t = driver(device.port) {
                object : Socket() {
                    override fun getInputStream(): InputStream {
                        val real = super.getInputStream()
                        return object : InputStream() {
                            override fun read(): Int = check().let { real.read() }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = check().let { real.read(b, off, len) }
                            private fun check() {
                                if (armed.count == 0L) throw SocketException("recvfrom failed: ECONNABORTED (Software caused connection abort)")
                            }
                        }
                    }
                }
            }
            withTimeout(5_000) { t.open(sink) }
            armed.countDown()
            // The reader is parked in a read that predates the abort. The answer wakes it and its
            // next read sees the error.
            t.writeControl(byteArrayOf(Opcode.STATUS.toByte()))
            assertEquals(DisconnectCause.LocalAbort(errno = SocketErrors.ECONNABORTED), withTimeout(5_000) { sink.closed.await() })
        }
    }

    @Test
    fun `an oversize length header is a framing violation`() = runBlocking {
        FakeLanDevice(token, hello(payloadMax = 512), afterHello = { s ->
            s.getOutputStream().apply { write(byteArrayOf(0xFF.toByte(), 0x7F)); flush() }
        }) { emptyList() }.use { device ->
            device.start()
            val sink = RecordingSink()
            withTimeout(5_000) { driver(device.port).open(sink) }
            assertEquals(DisconnectCause.FramingViolation("frame exceeds payload_max"), withTimeout(5_000) { sink.closed.await() })
        }
    }

    @Test
    fun `cancelling open closes the socket at once`() = runBlocking {
        val holding = CountDownLatch(1)
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val exec = Executors.newSingleThreadExecutor()
        exec.submit { runCatching { server.accept().use { holding.await(30, TimeUnit.SECONDS) } } }
        try {
            val sink = RecordingSink()
            val t = driver(server.localPort)
            val job = launch(Dispatchers.Default) { t.open(sink) }
            delay(300)
            withTimeout(1_000) { job.cancelAndJoinCompat() }
            assertTrue(withTimeout(1_000) { sink.closed.await() } is DisconnectCause.Deliberate)
            assertTrue(t.connectionState.value is ConnectionState.Disconnected)
        } finally {
            holding.countDown()
            server.close()
            exec.shutdownNow()
        }
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinCompat() {
        cancel()
        join()
    }

    private suspend fun openFails(device: FakeLanDevice?, port: Int, key: ByteArray = token): LinkClosedException {
        device?.start()
        val sink = RecordingSink()
        val e = try {
            withTimeout(10_000) { driver(port, key).open(sink) }
            fail("expected LinkClosedException")
            error("unreachable")
        } catch (e: LinkClosedException) {
            e
        }
        assertEquals(e.disconnectCause, sink.closed.await())
        return e
    }

    @Test
    fun `wrong token is Auth TOKEN_REJECTED`() = runBlocking {
        FakeLanDevice(token, hello()) { emptyList() }.use {
            val e = openFails(it, it.port, ByteArray(32) { 0x42 })
            assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), e.disconnectCause)
            assertEquals(LanFailure.AUTH_FAILED, (e.cause as LanConnectException).failure)
        }
    }

    @Test
    fun `closing without HELLO is a rejected token not a hang`() = runBlocking {
        FakeLanDevice(token, hello = null) { emptyList() }.use {
            val e = openFails(it, it.port)
            assertEquals(LanFailure.AUTH_FAILED, (e.cause as LanConnectException).failure)
        }
    }

    @Test
    fun `busy device is Busy`() = runBlocking {
        FakeLanDevice(token, FrameCodec.encode(Frame.Error(0, ProtocolError.BUSY.code, ""))) { emptyList() }.use {
            val e = openFails(it, it.port)
            assertEquals(DisconnectCause.Busy, e.disconnectCause)
            assertEquals(LanFailure.BUSY, (e.cause as LanConnectException).failure)
        }
    }

    @Test
    fun `nothing listening is Unreachable`() = runBlocking {
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val e = openFails(null, free)
        assertEquals(DisconnectCause.Unreachable, e.disconnectCause)
        assertEquals(LanFailure.UNREACHABLE, (e.cause as LanConnectException).failure)
    }
}
