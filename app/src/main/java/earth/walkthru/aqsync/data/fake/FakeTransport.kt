package earth.walkthru.aqsync.data.fake

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * An in-process CoreS3 that speaks protocol v2 over the [AqTransport] interface, so every screen
 * and the whole sync path can be exercised without hardware.
 *
 * It serves three small `PAR1…PAR1` files plus one 1 MiB benchmark file, all with correct CRC-32s, emits `live` JSON every 10 s and
 * `status` after every sample, honours `max_read`, and answers `SET_TIME` / `FLUSH` like the
 * firmware would. v2: a fake config (pairing mode, Wi-Fi, LAN), a fake Wi-Fi scan list, a fake
 * token, a fake serial log ring, and `REBOOT` that drops the link and resets boot state. Enabled
 * from the debug-only menu entry on the scan screen.
 *
 * [simulateLan] makes the fake *behave* like the LAN pipe: `payload_max` 1024 and `GET_TOKEN`
 * answered with `ERROR` 13, so the LAN-specific UI paths can be seen without a network. It is not
 * a socket; the real [earth.walkthru.aqsync.data.lan.TcpLinkDriver] is only testable on hardware.
 */
class FakeTransport(
    override val address: String = FAKE_ADDRESS,
    private val liveIntervalMs: Long = 10_000,
    private val chunkDelayMs: Long = 4,
    seed: Int = 7,
    private val simulateLan: Boolean = false,
    val station: String = "53315f5f-cb85-4d8d-b623-d56266084189",
    val deviceId: String = "441bf6e26b40",
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AqTransport {

    override val displayName: String = "AQ-6b40"
    override val kind: TransportKind = if (simulateLan) TransportKind.LAN else TransportKind.FAKE
    override val endpoint: String get() = if (simulateLan) "fake 192.168.1.42:47390" else "in-process fake"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()
    override val mtu: Int get() = if (simulateLan) 1024 + 3 else 517

    private val _status = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live

    // Replaced on every connect so the same fake device (and its configuration) survives
    // close() + reconnect, e.g. across a fake REBOOT.
    private var _responses = Channel<ByteArray>(Channel.UNLIMITED)
    override val responseFrames: ReceiveChannel<ByteArray> get() = _responses

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var liveJob: Job? = null
    private val rnd = Random(seed)

    // --------------------------------------------------------------- device state

    // Identities observed on real hardware (bench run 2026-09, AQ-6b40) so fixtures look authentic.
    val bootId = "b41d37a48c04031e9397e28ce48c2f43"
    val maxRead = 16_384

    private var upSeconds = 12_345L
    private var seq = 15_021L
    private var buffered = 3
    private var utcAnchorNs: Long? = null
    private var clockGen = 0
    private var finalized: Long

    // ---- v2 configuration state (mirrors config::Settings in the firmware)
    private var pairMode = "random"
    private var pin = 123_456
    private var pendingPairMode: String? = null
    private var pendingPin: Int? = null
    private var bonds = 1
    private val display = 1
    private var wifiOn = false
    private var ssid = ""
    private var pskSet = false
    private var wifiState = "off"
    private var wifiIp = ""
    private var wifiRssi = 0
    private var lanOn = true
    private var lanClients = if (simulateLan) 1 else 0
    private var token: ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    private var wifiJob: Job? = null
    private val bootTimeMs = System.currentTimeMillis()

    /** 8 KiB ring of "serial" output, like the firmware's debug_log. */
    private val log = StringBuilder()
    private var logTotal = 0L

    private fun logLine(line: String) {
        val stamped = "[%8d] %s\n".format(java.util.Locale.ROOT, (System.currentTimeMillis() - bootTimeMs) / 1000 + upSeconds, line)
        log.append(stamped)
        logTotal += stamped.length
        while (log.length > LOG_RING) log.delete(0, log.length - LOG_RING)
    }

    val files: MutableList<FakeFile> = mutableListOf(
        FakeFile("station=$station/year=2026/month=09/day=15/data_1000_${bootId}_15000-15089-90.parquet", fakeParquet(rnd, 24_000)),
        FakeFile("station=$station/year=2026/month=09/day=15/data_1015_${bootId}_15090-15179-90.parquet", fakeParquet(rnd, 23_400)),
        FakeFile("station=$station/unsynced/boot=$bootId/data_0000_${bootId}_1-60-60.parquet", fakeParquet(rnd, 9_800)),
        // One file big enough (≈ 8 s at the fake's chunk pace) to watch the progress notification,
        // the Stop action and a resume from `.partial` without hardware.
        FakeFile("benchmarks/boot=$bootId/bench_lz4_raw_${bootId}_1-6000-6000.parquet", fakeParquet(rnd, 1_048_576)),
    )

    init {
        finalized = files.size.toLong()
        logLine("BOOT station=$station dev=$deviceId boot=$bootId fw=idf-cores3-parquet-v6.12 (fake)")
        logLine("PARQUET STATUS files=$finalized codec=LZ4_RAW sd=1")
        logLine("BLE START name=$displayName pair=$pairMode")
        logLine("WIFI OFF (wifi.on=0)")
    }

    private var openHandle: Int? = null
    private var openFile: FakeFile? = null
    private var nextHandle = 1

    // ------------------------------------------------------------ fault injection (tests)

    /** Number of `READ` requests handled so far; the hooks below are keyed on it (1-based). */
    @Volatile var readCount: Int = 0
        private set

    /**
     * Test hook reproducing the lost-burst symptom seen on a phone: for the k-th `READ` window (1-based),
     * return how many of its *leading* `CHUNK` frames to drop. `READ_END` is still sent with the
     * device's true `next_offset`, exactly like the firmware whose notifications never reached the
     * phone. Returns 0 by default (lossless).
     */
    @Volatile var dropLeadingChunks: (readIndex: Int) -> Int = { 0 }

    /** Test hook: delay the `CLOSED` reply by this many ms, so a caller's timeout fires first. */
    @Volatile var closedDelayMs: Long = 0

    class FakeFile(val name: String, val bytes: ByteArray) {
        val crc32: Long = ParquetVerify.crc32(bytes)
    }

    // ------------------------------------------------------------------ transport

    override suspend fun connect() {
        if (_state.value.isReady) return
        if (_responses.isClosedForSend) _responses = Channel(Channel.UNLIMITED)
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

    /**
     * Unlike the real transports this instance stays usable after close(): the factory hands out
     * one fake per address so device state (config, token, log, files) persists like NVS would.
     */
    override fun close() {
        liveJob?.cancel()
        liveJob = null
        openHandle = null
        openFile = null
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

    // The op whose reply is streaming, so ABORT can stop it like proto 3 firmware does.
    private var streaming = 0
    private var abortRequested = false

    private fun stoppedByAbort(op: Int): Boolean {
        if (!abortRequested) return false
        abortRequested = false
        streaming = 0
        error(op, ProtocolError.ABORTED)
        send(Frame.Aborted(op))
        return true
    }

    private suspend fun handle(op: Int, buf: ByteBuffer) {
        when (op) {
            Opcode.ABORT -> if (streaming != 0) abortRequested = true else send(Frame.Aborted(0))
            Opcode.LIST -> {
                streaming = op
                for (f in files) {
                    if (stoppedByAbort(op)) return
                    send(Frame.File(f.bytes.size.toLong(), f.name))
                    delay(2)
                }
                streaming = 0
                send(Frame.ListEnd(files.size.toLong(), 1, SD_KIB, sdUsedKib()))
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
                var toDrop = dropLeadingChunks(++readCount)
                var pos = offset
                streaming = op
                while (pos < end) {
                    if (stoppedByAbort(op)) return
                    val n = minOf(chunkMax.toLong(), end - pos).toInt()
                    if (toDrop > 0) toDrop-- else send(Frame.Chunk(handle, pos, f.bytes.copyOfRange(pos.toInt(), pos.toInt() + n)))
                    pos += n
                    if (chunkDelayMs > 0) delay(chunkDelayMs)
                }
                streaming = 0
                send(Frame.ReadEnd(handle, pos, 0))
            }
            Opcode.CLOSE -> {
                if (buf.remaining() < 2) return error(op, ProtocolError.MALFORMED_REQUEST)
                val handle = buf.short.toInt() and 0xFFFF
                if (handle != openHandle) return error(op, ProtocolError.BAD_HANDLE)
                openHandle = null
                openFile = null
                if (closedDelayMs > 0) delay(closedDelayMs)
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

            // ------------------------------------------------------------ v2
            Opcode.GET_CONFIG -> send(Frame.Config(configFlags(), configJson()))
            Opcode.SET_CONFIG -> {
                val text = String(ByteArray(buf.remaining()).also { buf.get(it) }, Charsets.UTF_8)
                val bad = applyConfigLines(text)
                if (bad != null) return error(op, ProtocolError.INVALID_CONFIG, bad)
                send(Frame.Config(configFlags(), configJson()))
                _status.tryEmit(statusJson().encodeToByteArray())
            }
            Opcode.WIFI_SCAN -> {
                logLine("WIFI SCAN start")
                delay(2_500)
                val aps = listOf(
                    Frame.WifiAp(-48, 7, 6, "home"),
                    Frame.WifiAp(-58, 3, 11, "walkthru-field"),
                    Frame.WifiAp(-67, 0, 1, "CoffeeShop Guest"),
                    Frame.WifiAp(-72, 5, 36, "eduroam"),
                    Frame.WifiAp(-81, 6, 44, "Neighbour 5G"),
                    Frame.WifiAp(-88, 255, 3, "☕ café ünïcode"),
                )
                for (ap in aps) {
                    send(ap)
                    delay(3)
                }
                logLine("WIFI SCAN done n=${aps.size}")
                send(Frame.WifiScanEnd(aps.size, 0))
            }
            Opcode.REBOOT -> {
                logLine("REBOOT requested over ${if (simulateLan) "lan" else "ble"}")
                if (buffered > 0) {
                    // "finalizes any RAM batch first"
                    val rows = buffered
                    val first = seq - rows + 1
                    val dir = if (utcAnchorNs != null) "station=$station/year=2026/month=09/day=16" else "station=$station/unsynced/boot=$bootId"
                    files += FakeFile("$dir/data_reboot_${bootId}_$first-$seq-$rows.parquet", fakeParquet(rnd, 2_000 + rows * 120))
                    finalized++
                    buffered = 0
                }
                send(Frame.Rebooting(500))
                scope.launch {
                    delay(500)
                    rebootNow()
                }
            }
            Opcode.LOG_TAIL -> {
                if (buf.remaining() < 2) return error(op, ProtocolError.MALFORMED_REQUEST)
                val max = minOf(buf.short.toInt() and 0xFFFF, LOG_RING)
                val all = log.toString().encodeToByteArray()
                val slice = if (all.size > max) all.copyOfRange(all.size - max, all.size) else all
                val step = (mtu - 3) - 1
                var pos = 0
                while (pos < slice.size) {
                    val n = minOf(step, slice.size - pos)
                    send(Frame.Log(slice.copyOfRange(pos, pos + n)))
                    pos += n
                    delay(2)
                }
                send(Frame.LogEnd(logTotal, slice.size))
            }
            Opcode.GET_TOKEN -> {
                if (simulateLan) return error(op, ProtocolError.NOT_ALLOWED_ON_LINK)
                logLine("BLE GET_TOKEN")
                send(Frame.Token(LAN_PORT, token.copyOf()))
            }
            else -> error(op, ProtocolError.UNKNOWN_OPCODE)
        }
    }

    // ------------------------------------------------------------- v2 helpers

    private fun configFlags(): Int = if (pendingPairMode != null || pendingPin != null) 1 else 0

    private fun configJson(): String {
        val effectivePin = pendingPin ?: pin
        val mac = "e0:5a:1b:f6:6b:40"
        return """{"ble":{"pair":"${pendingPairMode ?: pairMode}","pin":$effectivePin,"pin_default":${if (effectivePin == 123_456) 1 else 0},"bonds":$bonds,"display":$display},""" +
            """"wifi":{"on":${if (wifiOn) 1 else 0},"ssid":"${ssid.replace("\"", "\\\"")}","psk_set":${if (pskSet) 1 else 0},"state":"$wifiState","ip":"$wifiIp","rssi":$wifiRssi,"mac":"$mac"},""" +
            """"lan":{"on":${if (lanOn) 1 else 0},"port":$LAN_PORT,"host":"aq-6b40","clients":$lanClients}}"""
    }

    /** Validate every line first (one bad key rejects all), then apply. Returns the bad key or null. */
    private fun applyConfigLines(text: String): String? {
        val parsed = ArrayList<Pair<String, String>>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq < 0) return line
            parsed += line.substring(0, eq) to line.substring(eq + 1)
        }
        for ((k, v) in parsed) {
            val ok = when (k) {
                "ble.pair" -> v in setOf("random", "fixed", "none")
                "ble.pin" -> v.length == 6 && v.all { it.isDigit() }
                "ble.clear_bonds", "lan.rotate_token" -> v == "1"
                "wifi.on", "lan.on" -> v == "0" || v == "1"
                "wifi.ssid" -> v.toByteArray().size in 1..32
                "wifi.psk" -> v.toByteArray().size <= 63
                else -> false
            }
            if (!ok) return k
        }
        var wifiChanged = false
        for ((k, v) in parsed) {
            when (k) {
                "ble.pair" -> if (v != pairMode) pendingPairMode = v else pendingPairMode = null
                "ble.pin" -> v.toInt().let { if (it != pin) pendingPin = it else pendingPin = null }
                "ble.clear_bonds" -> { bonds = 0; logLine("BLE BONDS cleared") }
                "lan.rotate_token" -> { token = ByteArray(32).also { rnd.nextBytes(it) }; lanClients = 0; logLine("LAN TOKEN rotated; sessions dropped") }
                "wifi.on" -> { wifiOn = v == "1"; wifiChanged = true }
                "wifi.ssid" -> { ssid = v; wifiChanged = true }
                "wifi.psk" -> { pskSet = v.isNotEmpty(); wifiChanged = true }
                "lan.on" -> { lanOn = v == "1"; logLine("LAN ${if (lanOn) "ON" else "OFF"}") }
            }
        }
        logLine("CONFIG SET keys=${parsed.joinToString(",") { it.first }}")
        if (wifiChanged) applyWifi()
        return null
    }

    /** off → connecting → connected (or failed for an unknown SSID), like the firmware's 30 s retry loop, but faster. */
    private fun applyWifi() {
        wifiJob?.cancel()
        if (!wifiOn || ssid.isEmpty()) {
            wifiState = "off"
            wifiIp = ""
            wifiRssi = 0
            lanClients = 0
            logLine("WIFI OFF")
            return
        }
        wifiState = "connecting"
        wifiIp = ""
        wifiRssi = 0
        logLine("WIFI CONNECT ssid=$ssid")
        wifiJob = scope.launch {
            delay(3_000)
            val known = ssid == "home" || ssid == "walkthru-field" || ssid == "CoffeeShop Guest"
            if (known && (pskSet || ssid == "CoffeeShop Guest")) {
                wifiState = "connected"
                wifiIp = "192.168.1.42"
                wifiRssi = -58
                logLine("WIFI CONNECTED ip=$wifiIp rssi=$wifiRssi")
                if (lanOn) logLine("LAN LISTEN port=$LAN_PORT mdns=_aqsync._tcp host=aq-6b40")
            } else {
                wifiState = "failed"
                logLine("WIFI FAILED ssid=$ssid (fake: only 'home', 'walkthru-field', 'CoffeeShop Guest' exist)")
            }
            _status.tryEmit(statusJson().encodeToByteArray())
        }
    }

    /** Apply pending `ble.*` settings, reset boot counters, drop the link. */
    private suspend fun rebootNow() {
        pendingPairMode?.let { pairMode = it }
        pendingPin?.let { pin = it }
        pendingPairMode = null
        pendingPin = null
        upSeconds = 0
        clockGen = 0
        utcAnchorNs = null
        log.setLength(0)
        logTotal = 0
        logLine("BOOT (fake reboot) pair=$pairMode pin_default=${if (pin == 123_456) 1 else 0}")
        wifiJob?.cancel()
        if (wifiOn && ssid.isNotEmpty()) applyWifi() else wifiState = "off"
        disconnect()
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
        """{"proto":3,"fw":"idf-cores3-parquet-v6.12 (fake)","schema":"cores3-telemetry-v5","cols":81,""" +
            """"dict":"${LiveProfiles.CORES3_DICTIONARY}","station":"$station","dev":"$deviceId","boot":"$bootId","max_read":$maxRead}"""

    private fun statusJson(): String =
        """{"up_s":$upSeconds,"int_s":900,"buf":$buffered,"fin":$finalized,"drop":0,"err":0,"miss":0,""" +
            """"fail":0,"codec":"LZ4_RAW","utc":${if (utcAnchorNs != null) 1 else 0},"gen":$clockGen,"sd":1,""" +
            """"sd_kib":$SD_KIB,"sd_used_kib":${sdUsedKib()},"heap":180000,"part":1}"""

    private fun liveJson(): String {
        val pm1 = (pm25 * 0.7).toInt()
        val pm10 = (pm25 * 1.3).toInt()
        val utc = utcAnchorNs?.let { """"utc":${it + upSeconds * 1_000_000_000L},""" } ?: ""
        return """{"seq":$seq,"mono":${upSeconds * 1_000_000},$utc"pms":4,""" +
            """"pm1":$pm1,"pm25":${pm25.toInt()},"pm10":$pm10,"c1":$pm1,"c25":${pm25.toInt()},"c10":$pm10,""" +
            """"n03":${(pm25 * 130).toInt()},"n05":${(pm25 * 36).toInt()},"n1":${(pm25 * 4).toInt()},"n25":2,"n5":0,"n10":0,""" +
            """"imu_t":${"%.1f".format(java.util.Locale.ROOT, 26.0 + rnd.nextDouble(-0.3, 0.3))},"bat":4100,"pct":80,"chg":0,"vbus":5000,"als":120}"""
    }

    private fun sdUsedKib(): Long = 34_176 + files.sumOf { it.bytes.size.toLong() } / 1024

    companion object {
        const val FAKE_ADDRESS = "FA:KE:00:00:00:01"
        const val FAKE_LAN_ADDRESS = "FA:KE:00:00:00:02"
        private const val SD_KIB = 31_166_976L
        private const val LOG_RING = 8192
        private const val LAN_PORT = 47390

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
