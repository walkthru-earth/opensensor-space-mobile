package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Request
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration

/**
 * A driver whose peer is the test. [respond] runs on every write and may [emit] reply frames
 * at once. Everything else is emitted by the test when it wants.
 */
@OptIn(ExperimentalAtomicApi::class)
class ScriptedLinkDriver(
    override val kind: TransportKind = TransportKind.LAN,
    private val info: DeviceInfo = DeviceInfo(proto = 3),
    var respond: ScriptedLinkDriver.(op: Int, body: ByteArray) -> Unit = { _, _ -> },
) : LinkDriver {
    lateinit var sink: FrameSink
        private set

    /** Opcodes written, in order. */
    val sent = mutableListOf<Int>()
    private val aborts = AtomicInt(0)
    val abortCount: Int get() = aborts.load()

    override suspend fun open(sink: FrameSink): LinkInfo {
        this.sink = sink
        return LinkInfo(kind, payloadMax = 244, info = info)
    }

    override suspend fun send(body: ByteArray, deadline: Duration) {
        val op = body[0].toInt() and 0xFF
        sent += op
        respond(op, body)
    }

    override fun abort(cause: DisconnectCause) {
        aborts.incrementAndFetch()
        // A link that was never opened has no sink to tell.
        if (::sink.isInitialized) sink.onClosed(cause)
    }

    fun emit(vararg frames: Frame) = frames.forEach { sink.onFrame(FrameCodec.encode(it)) }

    /** A LAN `STATUS` frame, the answer to a ping. */
    fun status(json: String) = sink.onFrame(byteArrayOf(FrameType.STATUS.toByte()) + json.encodeToByteArray())

    fun eof() = sink.onClosed(DisconnectCause.PeerClosed())
}

/** All reply frames of [request], the way a typed API built on the session reads them. */
suspend fun LinkSession.collect(request: Request): List<Frame> {
    val frames = mutableListOf<Frame>()
    return exchange(RequestSpec.of(request)) { frame, terminal ->
        frames += frame
        if (terminal) frames.toList() else null
    }
}

/** A minimal typed API, so a test can build two independent callers on one session. */
class TinyApi(private val session: LinkSession) {
    suspend fun list(): List<String> = session.collect(Request.List).filterIsInstance<Frame.File>().map { it.name }

    suspend fun open(name: String): Frame = session.collect(Request.Open(name)).last()

    suspend fun close(handle: Int): Frame = session.collect(Request.Close(handle)).last()

    suspend fun getConfig(): Frame = session.collect(Request.GetConfig).last()
}
