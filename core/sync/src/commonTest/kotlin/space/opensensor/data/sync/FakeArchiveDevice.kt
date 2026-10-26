package space.opensensor.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.DeviceTransport
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.ProtocolError
import kotlin.random.Random

/**
 * The file half of a CoreS3 over [DeviceTransport], enough for the engine: LIST, OPEN, READ in CHUNKs of
 * `mtu - 10` bytes and CLOSE. Replies are sent from [scope] like the firmware's storage worker, so
 * a test's virtual clock drives every delay. Fault hooks match Android's debug `FakeTransport`.
 */
class FakeArchiveDevice(
    private val scope: CoroutineScope,
    override val address: String = "fake-0",
    val thing: String = "53315f5f-cb85-4d8d-b623-d56266084189",
    val maxRead: Int = 16_384,
    override val mtu: Int = 517,
    override val kind: TransportKind = TransportKind.FAKE,
) : DeviceTransport {
    class FakeFile(val name: String, val bytes: ByteArray) {
        val crc32: Long = ParquetVerify.crc32(bytes)
    }

    val files: MutableList<FakeFile> = mutableListOf(
        FakeFile("station=$thing/year=2026/month=09/day=15/data_1000_b41d_15000-15089-90.parquet", fakeParquet(1, 24_000)),
        FakeFile("station=$thing/year=2026/month=09/day=15/data_1015_b41d_15090-15179-90.parquet", fakeParquet(2, 23_400)),
        FakeFile("station=$thing/unsynced/boot=b41d/data_0000_b41d_1-60-60.parquet", fakeParquet(3, 9_800)),
    )

    /** The offset of every `READ` handled, in order, so a test can see where a resume started. */
    val readOffsets: MutableList<Long> = mutableListOf()

    /** The length asked by every `READ` handled, in order, so a test can follow the adaptive window. */
    val readLengths: MutableList<Long> = mutableListOf()

    /** `READ` requests handled so far. The hooks below are keyed on it (1-based). */
    var readCount: Int = 0
        private set

    /** Leading CHUNKs of the k-th READ window to drop, as a lossy BLE link does. READ_END still says the true offset. */
    var dropLeadingChunks: (readIndex: Int) -> Int = { 0 }

    /** True for a READ (1-based) that sends no frame until an `ABORT` stops it, as a stalled link does. */
    var stallRead: (readIndex: Int) -> Boolean = { false }

    /**
     * CHUNKs the k-th READ (1-based) sends before it goes silent until an `ABORT`, so the link times
     * out between frames instead of before the first one. Negative never stalls.
     */
    var stallAfterChunks: (readIndex: Int) -> Int = { -1 }

    /** True for a request write that never completes, as a write stuck on a full link does. */
    var hangWrite: (op: Int) -> Boolean = { false }

    /** Request writes started so far, by opcode, in order, including any that [hangWrite] holds. */
    val writes: MutableList<Int> = mutableListOf()

    /** Runs before an OPEN is answered, so a test can change the files between LIST and OPEN. */
    var beforeOpen: (name: String) -> Unit = {}

    /** An `ERROR` to answer this OPEN with instead of OPENED, such as a refused token mid-sync. */
    var refuseOpen: (name: String) -> Frame.Error? = { null }

    /** Delay before CLOSED, so a caller's timeout fires first. */
    var closedDelayMs: Long = 0

    /** Optional accepted clock evidence. Null retains the fake's default SET_TIME refusal. */
    var clockAck: Frame.TimeSet? = null

    /** Optional SET_TIME error, including a global error for op 0. Takes precedence over an ack. */
    var clockError: Frame.Error? = null

    /** Delay between CHUNKs, so concurrent transfers interleave. */
    var chunkDelayMs: Long = 0

    /** Delay before the first LIST frame. An `ABORT` meanwhile ends the LIST as firmware does. */
    var listDelayMs: Long = 0

    /** Runs before the k-th READ (1-based) is answered, so a test can drop the link mid-transfer. */
    var beforeRead: (readIndex: Int) -> Unit = {}

    /** Runs before a LIST is answered, so a test can drop the link mid-preparation. */
    var beforeList: () -> Unit = {}

    private val responses = Channel<ByteArray>(Channel.UNLIMITED)
    private val statusUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready(mtu))
    private var openFile: FakeFile? = null
    private var openHandle = 0
    private var nextHandle = 1

    /** The op whose reply is streaming, so `ABORT` can stop it like proto 3 firmware does. */
    private var streaming = 0
    private var abortRequested = false

    /** Requests received, by opcode, in order. */
    val requests: MutableList<Int> = mutableListOf()

    override val displayName: String = "OS-fake"
    override val endpoint: String = "in-process fake"
    override val connectionState: StateFlow<ConnectionState> = state
    override val statusNotifications: Flow<ByteArray> = statusUpdates
    override val liveNotifications: Flow<ByteArray> = emptyFlow()
    override val responseFrames: ReceiveChannel<ByteArray> get() = responses

    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun readInfo(): ByteArray = """{"proto":3,"station":"$thing","max_read":$maxRead}""".encodeToByteArray()
    override suspend fun readStatus(): ByteArray = "{}".encodeToByteArray()
    override suspend fun readLive(): ByteArray = "{}".encodeToByteArray()
    override fun close() {
        responses.close()
    }

    override suspend fun writeControl(request: ByteArray) {
        val op = request[0].toInt() and 0xFF
        writes += op
        if (hangWrite(op)) awaitCancellation()
        scope.launch { handle(request) }
    }

    private fun send(frame: Frame) {
        responses.trySend(FrameCodec.encode(frame))
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    private suspend fun handle(req: ByteArray) {
        val op = req[0].toInt() and 0xFF
        requests += op
        when (op) {
            Opcode.ABORT -> if (streaming != 0) abortRequested = true else send(Frame.Aborted(0))
            Opcode.SET_TIME -> clockError?.let(::send) ?: clockAck?.let(::send) ?: send(Frame.Error(op, ProtocolError.MALFORMED_REQUEST.code, ""))
            Opcode.STATUS -> statusUpdates.emit("{}".encodeToByteArray())
            Opcode.LIST -> {
                beforeList()
                if (listDelayMs > 0) {
                    streaming = op
                    delay(listDelayMs)
                    streaming = 0
                    if (abortRequested) {
                        abortRequested = false
                        send(Frame.Error(op, ProtocolError.ABORTED.code, ""))
                        return send(Frame.Aborted(op))
                    }
                }
                files.forEach { send(Frame.File(it.bytes.size.toLong(), it.name)) }
                send(Frame.ListEnd(files.size.toLong(), 0, 0, 0))
            }
            Opcode.OPEN -> {
                val name = req.copyOfRange(1, req.size).decodeToString()
                beforeOpen(name)
                refuseOpen(name)?.let { return send(it) }
                val f = files.firstOrNull { it.name == name } ?: return send(Frame.Error(op, ProtocolError.INVALID_NAME.code, ""))
                openHandle = nextHandle++
                openFile = f
                send(Frame.Opened(openHandle, f.bytes.size.toLong(), f.crc32, f.name))
            }
            Opcode.READ -> {
                val handle = u16(req, 1)
                val offset = u32(req, 3)
                val length = u32(req, 7)
                val f = openFile
                if (f == null || handle != openHandle) return send(Frame.Error(op, ProtocolError.BAD_HANDLE.code, ""))
                readOffsets += offset
                readLengths += length
                beforeRead(readCount + 1)
                val end = minOf(f.bytes.size.toLong(), offset + minOf(length, maxRead.toLong()))
                val chunkMax = mtu - 10
                var toDrop = dropLeadingChunks(++readCount)
                val stallAt = if (stallRead(readCount)) 0 else stallAfterChunks(readCount)
                var sent = 0
                var pos = offset
                streaming = op
                while (pos < end || sent == stallAt) {
                    if (sent == stallAt) while (!abortRequested) delay(100)
                    if (abortRequested) {
                        abortRequested = false
                        streaming = 0
                        send(Frame.Error(op, ProtocolError.ABORTED.code, ""))
                        return send(Frame.Aborted(op))
                    }
                    val n = minOf(chunkMax.toLong(), end - pos).toInt()
                    if (toDrop > 0) toDrop-- else send(Frame.Chunk(handle, pos, f.bytes.copyOfRange(pos.toInt(), pos.toInt() + n)))
                    sent++
                    pos += n
                    if (chunkDelayMs > 0) delay(chunkDelayMs)
                }
                streaming = 0
                send(Frame.ReadEnd(handle, pos, 0))
            }
            Opcode.CLOSE -> {
                val handle = u16(req, 1)
                if (handle != openHandle) return send(Frame.Error(op, ProtocolError.BAD_HANDLE.code, ""))
                openFile = null
                if (closedDelayMs > 0) delay(closedDelayMs)
                send(Frame.Closed(handle))
            }
            else -> send(Frame.Error(op, ProtocolError.MALFORMED_REQUEST.code, ""))
        }
    }

    companion object {
        /** `PAR1`, seeded noise, `PAR1`: what [ParquetVerify] checks, nothing more. */
        fun fakeParquet(seed: Int, size: Int): ByteArray {
            val bytes = Random(seed).nextBytes(size)
            ParquetVerify.MAGIC.copyInto(bytes, 0)
            ParquetVerify.MAGIC.copyInto(bytes, size - ParquetVerify.MAGIC.size)
            return bytes
        }
    }
}
