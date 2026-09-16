package earth.walkthru.aqsync.data.fake

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * An in-process CoreS3 that speaks protocol v1 over the [AqTransport] interface, so every screen
 * and the whole sync path can be exercised without hardware.
 *
 * It serves three small `PAR1…PAR1` files with correct CRC-32s, emits `live` JSON every 10 s and
 * `status` after every sample, honours `max_read`, and answers `SET_TIME` / `FLUSH` like the
 * firmware would. Enabled from the debug-only menu entry on the scan screen.
 */
class FakeTransport(
    override val address: String = FAKE_ADDRESS,
    private val liveIntervalMs: Long = 10_000,
    private val chunkDelayMs: Long = 4,
    seed: Int = 7,
) : AqTransport {

    override val displayName: String = "AQ-6b40"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()
    override val mtu: Int get() = 517

    private val _status = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live
    private val _responses = Channel<ByteArray>(Channel.UNLIMITED)
    override val responseFrames: ReceiveChannel<ByteArray> = _responses

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var liveJob: Job? = null
    private val rnd = Random(seed)

    // --------------------------------------------------------------- device state

    // Identities observed on real hardware (bench run 2026-09, AQ-6b40) so fixtures look authentic.
    val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    val deviceId = "441bf6e26b40"
    val bootId = "b41d37a48c04031e9397e28ce48c2f43"
    val maxRead = 16_384

    private var upSeconds = 12_345L
    private var seq = 15_021L
    private var buffered = 3
    private var utcAnchorNs: Long? = null
    private var clockGen = 0
    private var finalized: Long

    val files: MutableList<FakeFile> = mutableListOf(
        FakeFile("station=$station/year=2026/month=09/day=15/data_1000_${bootId}_15000-15089-90.parquet", fakeParquet(rnd, 24_000)),
        FakeFile("station=$station/year=2026/month=09/day=15/data_1015_${bootId}_15090-15179-90.parquet", fakeParquet(rnd, 23_400)),
        FakeFile("station=$station/unsynced/boot=$bootId/data_0000_${bootId}_1-60-60.parquet", fakeParquet(rnd, 9_800)),
    )

    init {
        finalized = files.size.toLong()
    }

    private var openHandle: Int? = null
    private var openFile: FakeFile? = null
    private var nextHandle = 1

    class FakeFile(val name: String, val bytes: ByteArray) {
        val crc32: Long = ParquetVerify.crc32(bytes)
    }

    // ------------------------------------------------------------------ transport

    override suspend fun connect() {
        if (_state.value.isReady) return
        _state.value = ConnectionState.Connecting
        delay(400)
        _state.value = ConnectionState.DiscoveringServices
        delay(300)
        _state.value = ConnectionState.NegotiatingMtu
        delay(150)
        _state.value = ConnectionState.Bonding
        delay(600)
        _state.value = ConnectionState.EnablingNotifications
        delay(150)
        _state.value = ConnectionState.Ready(mtu)
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                emitSample()
                delay(liveIntervalMs)
            }
        }
    }

    override suspend fun disconnect() {
        liveJob?.cancel()
        liveJob = null
        openHandle = null
        openFile = null
        _state.value = ConnectionState.Disconnected
    }

    override fun close() {
        liveJob?.cancel()
        scope.cancel()
        _responses.close()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun readInfo(): ByteArray {
        requireReady()
        return infoJson().encodeToByteArray()
    }

    override suspend fun readStatus(): ByteArray {
        requireReady()
        return statusJson().encodeToByteArray()
    }

    override suspend fun readLive(): ByteArray {
        requireReady()
        return liveJson().encodeToByteArray()
    }

    override suspend fun writeControl(request: ByteArray) {
        requireReady()
        if (request.isEmpty()) return error(0, ProtocolError.MALFORMED_REQUEST)
        val buf = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        val op = buf.get().toInt() and 0xFF
        // The firmware runs commands on its storage worker; answer asynchronously like it would.
        scope.launch { handle(op, buf) }
    }

    private fun requireReady() {
        if (!_state.value.isReady) throw TransportException("fake device not connected")
    }

    // -------------------------------------------------------------------- commands

    private suspend fun handle(op: Int, buf: ByteBuffer) {
        when (op) {
            Opcode.LIST -> {
                for (f in files) {
                    send(Frame.File(f.bytes.size.toLong(), f.name))
                    delay(2)
                }
                send(Frame.ListEnd(files.size, 1, SD_KIB, sdUsedKib()))
            }
            Opcode.OPEN -> {
                val name = String(ByteArray(buf.remaining()).also { buf.get(it) }, Charsets.UTF_8)
                val f = files.firstOrNull { it.name == name }
                    ?: return error(op, ProtocolError.INVALID_NAME, name.take(40))
                // Device computes CRC over the whole file before answering.
                delay(150 + f.bytes.size / 200L)
                val handle = nextHandle++
                openHandle = handle
                openFile = f
                send(Frame.Opened(handle, f.bytes.size.toLong(), f.crc32, f.name))
            }
            Opcode.READ -> {
                if (buf.remaining() < 10) return error(op, ProtocolError.MALFORMED_REQUEST)
                val handle = buf.short.toInt() and 0xFFFF
                val offset = buf.int.toLong() and 0xFFFF_FFFFL
                val length = buf.int.toLong() and 0xFFFF_FFFFL
                val f = openFile
                if (f == null || handle != openHandle) return error(op, ProtocolError.BAD_HANDLE)
                if (offset > f.bytes.size) return error(op, ProtocolError.RANGE_OUTSIDE_FILE)
                val end = minOf(f.bytes.size.toLong(), offset + minOf(length, maxRead.toLong()))
                val chunkMax = (mtu - 3) - 7
                var pos = offset
                while (pos < end) {
                    val n = minOf(chunkMax.toLong(), end - pos).toInt()
                    send(Frame.Chunk(handle, pos, f.bytes.copyOfRange(pos.toInt(), pos.toInt() + n)))
                    pos += n
                    if (chunkDelayMs > 0) delay(chunkDelayMs)
                }
                send(Frame.ReadEnd(handle, pos, 0))
            }
            Opcode.CLOSE -> {
                if (buf.remaining() < 2) return error(op, ProtocolError.MALFORMED_REQUEST)
                val handle = buf.short.toInt() and 0xFFFF
                if (handle != openHandle) return error(op, ProtocolError.BAD_HANDLE)
                openHandle = null
                openFile = null
                send(Frame.Closed(handle))
            }
            Opcode.SET_TIME -> {
                if (buf.remaining() < 8) return error(op, ProtocolError.MALFORMED_REQUEST)
                val epoch = buf.long
                if (epoch < 1_577_836_800L || epoch > 4_102_444_800L) return error(op, ProtocolError.INVALID_EPOCH)
                utcAnchorNs = epoch * 1_000_000_000L
                clockGen++
                send(Frame.TimeSet(epoch, upSeconds * 1_000_000L))
                _status.tryEmit(statusJson().encodeToByteArray())
            }
            Opcode.FLUSH -> {
                if (buffered == 0) return error(op, ProtocolError.NOTHING_TO_FLUSH)
                delay(400)
                val rows = buffered
                val first = seq - rows + 1
                val dir = if (utcAnchorNs != null) "station=$station/year=2026/month=09/day=16" else "station=$station/unsynced/boot=$bootId"
                files += FakeFile("$dir/data_flush_${bootId}_$first-$seq-$rows.parquet", fakeParquet(rnd, 2_000 + rows * 120))
                finalized++
                buffered = 0
                send(Frame.Flushed(rows, finalized))
                _status.tryEmit(statusJson().encodeToByteArray())
            }
            Opcode.STATUS -> _status.tryEmit(statusJson().encodeToByteArray())
            else -> error(op, ProtocolError.UNKNOWN_OPCODE)
        }
    }

    private fun send(frame: Frame) {
        _responses.trySend(FrameCodec.encode(frame))
    }

    private fun error(op: Int, code: ProtocolError, detail: String = "") {
        send(Frame.Error(op, code.code, detail))
    }

    // ------------------------------------------------------------------- telemetry

    private var pm25 = 7.0

    private fun emitSample() {
        upSeconds += liveIntervalMs / 1000
        seq++
        buffered++
        pm25 = (pm25 + rnd.nextDouble(-1.5, 1.5)).coerceIn(1.0, 60.0)
        _live.tryEmit(liveJson().encodeToByteArray())
        _status.tryEmit(statusJson().encodeToByteArray())
    }

    private fun infoJson(): String =
        """{"proto":1,"fw":"arduino-cores3-parquet-v4 (fake)","schema":"cores3-telemetry-v2","cols":77,""" +
            """"dict":"20f850852413abb1165f3f7ca95b4831a0e90a34ae7c7fcb41c44b108d3513c3","station":"$station","dev":"$deviceId","boot":"$bootId","max_read":$maxRead}"""

    private fun statusJson(): String =
        """{"up_s":$upSeconds,"int_s":900,"buf":$buffered,"fin":$finalized,"drop":0,"err":0,"miss":0,""" +
            """"fail":0,"codec":"LZ4_RAW","utc":${if (utcAnchorNs != null) 1 else 0},"gen":$clockGen,"sd":1,""" +
            """"sd_kib":$SD_KIB,"sd_used_kib":${sdUsedKib()},"heap":180000,"part":1}"""

    private fun liveJson(): String {
        val pm1 = (pm25 * 0.7).toInt()
        val pm10 = (pm25 * 1.3).toInt()
        val utc = utcAnchorNs?.let { """"utc":${it + upSeconds * 1_000_000_000L},""" } ?: ""
        return """{"seq":$seq,"mono":${upSeconds * 1_000_000},${utc}"pms":4,""" +
            """"pm1":$pm1,"pm25":${pm25.toInt()},"pm10":$pm10,"c1":$pm1,"c25":${pm25.toInt()},"c10":$pm10,""" +
            """"n03":${(pm25 * 130).toInt()},"n05":${(pm25 * 36).toInt()},"n1":${(pm25 * 4).toInt()},"n25":2,"n5":0,"n10":0,""" +
            """"t":${"%.1f".format(26.0 + rnd.nextDouble(-0.3, 0.3))},"bat":4100,"pct":80,"chg":0,"vbus":5000,"als":120}"""
    }

    private fun sdUsedKib(): Long = 34_176 + files.sumOf { it.bytes.size.toLong() } / 1024

    companion object {
        const val FAKE_ADDRESS = "FA:KE:00:00:00:01"
        private const val SD_KIB = 31_166_976L

        /** `PAR1` + random body + 4-byte footer length + `PAR1`. Not a real Parquet file, but passes the head/tail check. */
        fun fakeParquet(rnd: Random, size: Int): ByteArray {
            require(size >= ParquetVerify.MIN_SIZE)
            val out = ByteArray(size)
            rnd.nextBytes(out)
            ParquetVerify.MAGIC.copyInto(out, 0)
            ParquetVerify.MAGIC.copyInto(out, size - 4)
            val footerLen = (size - 12).coerceAtLeast(0)
            ByteBuffer.wrap(out, size - 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(footerLen)
            return out
        }
    }
}
