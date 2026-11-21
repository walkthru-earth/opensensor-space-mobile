package space.opensensor.demo

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
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.DeviceTransport
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * An in-process board that speaks protocol 3 over the [DeviceTransport] port, so every screen and
 * the whole sync path run without hardware on both phones (#310). Debug builds only. Android binds
 * it from its debug source set, iOS opens it when the framework is a debug binary.
 *
 * It serves three small `PAR1…PAR1` files plus one 1 MiB benchmark file, all with correct CRC-32s,
 * emits `live` JSON every [liveIntervalMs] and `status` after every sample, honours `max_read`, and
 * answers `SET_TIME` and `FLUSH` like the firmware. It also keeps a config (pairing mode, Wi-Fi,
 * LAN), a Wi-Fi scan list, a token, a serial log ring, and `REBOOT` that drops the link and resets
 * boot state. Its LIVE keys and values come from [template] ([DemoLive]), so it names no sensor.
 *
 * [simulateLan] makes it behave like the LAN pipe. `payload_max` is 1024 and `GET_TOKEN` is answered
 * with `ERROR` 13, so the LAN paths of the UI can be seen without a network. It is not a socket.
 */
class DemoSensor(
    override val address: String = ADDRESS,
    private val liveIntervalMs: Long = 10_000,
    private val chunkDelayMs: Long = 4,
    seed: Int = 7,
    private val simulateLan: Boolean = false,
    val thing: String = "53315f5f-cb85-4d8d-b623-d56266084189",
    val deviceId: String = "441bf6e26b40",
    val template: BoardTemplate = defaultTemplate(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DeviceTransport {

    override val displayName: String = "OS-6b40"
    override val kind: TransportKind = if (simulateLan) TransportKind.LAN else TransportKind.FAKE
    override val endpoint: String get() = if (simulateLan) "demo 192.168.1.42:$LAN_PORT" else "in-process demo"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()
    override val mtu: Int get() = if (simulateLan) 1024 + 3 else 517

    private val _status = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val statusNotifications: Flow<ByteArray> = _status
    override val liveNotifications: Flow<ByteArray> = _live

    // Replaced on every connect so the same board (and its configuration) survives close() and a
    // reconnect, for example across a REBOOT.
    private var _responses = Channel<ByteArray>(Channel.UNLIMITED)
    override val responseFrames: ReceiveChannel<ByteArray> get() = _responses

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var liveJob: Job? = null
    private val rnd = Random(seed)
    private val liveValues = DemoLive(template, rnd)

    // --------------------------------------------------------------- device state

    // Identities seen on real hardware (CoreS3 bench run 2026-09), so fixtures look authentic.
    val bootId = "b41d37a48c04031e9397e28ce48c2f43"
    val maxRead = 16_384

    private var upSeconds = 12_345L
    private var seq = 15_021L
    private var buffered = 3
    private var utcAnchorNs: Long? = null
    private var clockGen = 0
    private var finalized: Long

    // ---- configuration state (mirrors config::Settings in the firmware)
    private var pairMode = "random"
    private var pin = 123_456
    private var pendingPairMode: String? = null
    private var pendingPin: Int? = null
    private var bonds = 1
    private val display = 1
    private var wifiOn = false

    /** The location page `GET_CONFIG 1` answers, the saved cell and mobility. Tests and `SET_CONFIG` set them. */
    @Volatile var locationCell = ""

    @Volatile var mobility = "fixed"
    private var ssid = ""
    private var pskSet = false
    private var wifiState = "off"
    private var wifiIp = ""
    private var wifiRssi = 0
    private var lanOn = true
    private var lanClients = if (simulateLan) 1 else 0
    private var token: ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    private var wifiJob: Job? = null
    private val booted = TimeSource.Monotonic.markNow()

    /** A ring of "serial" output, as long as the most a `LOG_TAIL` returns. */
    private val log = StringBuilder()
    private var logTotal = 0L

    private fun logLine(line: String) {
        val stamp = (booted.elapsedNow().inWholeSeconds + upSeconds).toString().padStart(8)
        val stamped = "[$stamp] $line\n"
        log.append(stamped)
        logTotal += stamped.length
        while (log.length > LOG_RING) log.deleteRange(0, log.length - LOG_RING)
    }

    val files: MutableList<DemoFile> = mutableListOf(
        DemoFile("thing=$thing/year=2026/month=09/day=15/data_1000_${bootId}_15000-15089-90.parquet", demoParquet(rnd, 24_000)),
        DemoFile("thing=$thing/year=2026/month=09/day=15/data_1015_${bootId}_15090-15179-90.parquet", demoParquet(rnd, 23_400)),
        DemoFile("thing=$thing/unsynced/boot=$bootId/data_0000_${bootId}_1-60-60.parquet", demoParquet(rnd, 9_800)),
        // One file big enough (about 8 s at the chunk pace) to watch the progress notification,
        // the Stop action and a resume from `.partial` without hardware.
        DemoFile("benchmarks/boot=$bootId/bench_lz4_raw_${bootId}_1-6000-6000.parquet", demoParquet(rnd, 1_048_576)),
    )

    init {
        finalized = files.size.toLong()
        logLine("BOOT thing=$thing dev=$deviceId boot=$bootId fw=$FIRMWARE")
        logLine("PARQUET STATUS files=$finalized codec=LZ4_RAW sd=1")
        logLine("BLE START name=$displayName pair=$pairMode")
        logLine("WIFI OFF (wifi.on=0)")
    }

    private var openHandle: Int? = null
    private var openFile: DemoFile? = null
    private var nextHandle = 1

    // ------------------------------------------------------------ fault injection (tests)

    /** Number of `READ` requests handled so far. The hooks below are keyed on it (1-based). */
    @Volatile var readCount: Int = 0
        private set

    /**
     * Test hook for the lost burst seen on a phone. For the k-th `READ` window (1-based), how many
     * of its leading `CHUNK` frames to drop. `READ_END` still carries the true `next_offset`, like
     * firmware whose notifications never reached the phone. 0 by default (lossless).
     */
    @Volatile var dropLeadingChunks: (readIndex: Int) -> Int = { 0 }

    /** Test hook. Delays the `CLOSED` reply by this many ms, so a caller's timeout fires first. */
    @Volatile var closedDelayMs: Long = 0

    class DemoFile(val name: String, val bytes: ByteArray) {
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
     * Unlike the real transports this instance stays usable after close(). Each app keeps one
     * board per address, so its state (config, token, log, files) persists like NVS would.
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
        val body = Body(request)
        val op = body.u8()
        // The firmware runs commands on its storage worker. Answer asynchronously like it would.
        scope.launch { handle(op, body) }
    }

    private fun requireReady() {
        if (!_state.value.isReady) throw TransportException("demo sensor not connected")
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

    private suspend fun handle(op: Int, body: Body) {
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
                val name = body.rest().decodeToString()
                val f = files.firstOrNull { it.name == name }
                    ?: return error(op, ProtocolError.INVALID_NAME, name.take(40))
                // The device computes the CRC over the whole file before answering.
                delay(150 + f.bytes.size / 200L)
                val handle = nextHandle++
                openHandle = handle
                openFile = f
                send(Frame.Opened(handle, f.bytes.size.toLong(), f.crc32, f.name))
            }
            Opcode.READ -> {
                if (body.remaining < 10) return error(op, ProtocolError.MALFORMED_REQUEST)
                val handle = body.u16()
                val offset = body.u32()
                val length = body.u32()
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
                if (body.remaining < 2) return error(op, ProtocolError.MALFORMED_REQUEST)
                val handle = body.u16()
                if (handle != openHandle) return error(op, ProtocolError.BAD_HANDLE)
                openHandle = null
                openFile = null
                if (closedDelayMs > 0) delay(closedDelayMs)
                send(Frame.Closed(handle))
            }
            Opcode.SET_TIME -> {
                if (body.remaining < 8) return error(op, ProtocolError.MALFORMED_REQUEST)
                val epoch = body.i64()
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
                finalizeBatch("flush")
                send(Frame.Flushed(rows, finalized))
                _status.tryEmit(statusJson().encodeToByteArray())
            }
            Opcode.STATUS -> _status.tryEmit(statusJson().encodeToByteArray())

            // ---------------------------------------------- config, Wi-Fi, log, token
            Opcode.GET_CONFIG -> send(Frame.Config(configFlags(), if (body.remaining > 0 && body.u8() == 1) locationJson() else configJson()))
            Opcode.SET_CONFIG -> {
                val bad = applyConfigLines(body.rest().decodeToString())
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
                // The firmware finalizes any RAM batch first.
                if (buffered > 0) finalizeBatch("reboot")
                send(Frame.Rebooting(500))
                scope.launch {
                    delay(500)
                    rebootNow()
                }
            }
            Opcode.LOG_TAIL -> {
                if (body.remaining < 2) return error(op, ProtocolError.MALFORMED_REQUEST)
                val max = minOf(body.u16(), LOG_RING)
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

    /** Close the RAM batch into one more file, dated once the clock is set. */
    private fun finalizeBatch(label: String) {
        val rows = buffered
        val first = seq - rows + 1
        val dir = if (utcAnchorNs != null) "thing=$thing/year=2026/month=09/day=16" else "thing=$thing/unsynced/boot=$bootId"
        files += DemoFile("$dir/data_${label}_${bootId}_$first-$seq-$rows.parquet", demoParquet(rnd, 2_000 + rows * 120))
        finalized++
        buffered = 0
    }

    // ---------------------------------------------------------- config helpers

    private fun configFlags(): Int = if (pendingPairMode != null || pendingPin != null) 1 else 0

    private fun locationJson(): String =
        """{"location":{"cell":"$locationCell","resolution":8,"cell_resolution":${if (locationCell.isEmpty()) "null" else "8"},"lat":null,"lon":null,"country":""},"thing":{"mobility":"$mobility"}}"""

    private fun configJson(): String {
        val effectivePin = pendingPin ?: pin
        val mac = "e0:5a:1b:f6:6b:40"
        return """{"ble":{"pair":"${pendingPairMode ?: pairMode}","pin_set":1,"pin_default":${if (effectivePin == 123_456) 1 else 0},"bonds":$bonds,"display":$display},""" +
            """"wifi":{"on":${if (wifiOn) 1 else 0},"ssid":"${ssid.replace("\"", "\\\"")}","psk_set":${if (pskSet) 1 else 0},"state":"$wifiState","ip":"$wifiIp","rssi":$wifiRssi,"mac":"$mac"},""" +
            """"lan":{"on":${if (lanOn) 1 else 0},"port":$LAN_PORT,"host":"os-6b40","clients":$lanClients}}"""
    }

    /** Check every line first (one bad key refuses all), then apply. Returns the bad key or null. */
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
                "wifi.ssid" -> v.encodeToByteArray().size in 1..32
                "wifi.psk" -> v.encodeToByteArray().size <= 63
                "location.cell" -> v.isEmpty() || CELL.matches(v)
                "location.resolution" -> v.toIntOrNull() == 8
                "location.country" -> v.isEmpty() || COUNTRY.matches(v)
                "thing.mobility" -> v == "fixed" || v == "moving"
                else -> false
            }
            if (!ok) return k
        }
        var wifiChanged = false
        for ((k, v) in parsed) {
            when (k) {
                "ble.pair" -> pendingPairMode = if (v != pairMode) v else null
                "ble.pin" -> v.toInt().let { pendingPin = if (it != pin) it else null }
                "ble.clear_bonds" -> {
                    bonds = 0
                    logLine("BLE BONDS cleared")
                }
                "lan.rotate_token" -> {
                    token = ByteArray(32).also { rnd.nextBytes(it) }
                    lanClients = 0
                    logLine("LAN TOKEN rotated; sessions dropped")
                }
                "wifi.on" -> {
                    wifiOn = v == "1"
                    wifiChanged = true
                }
                "wifi.ssid" -> {
                    ssid = v
                    wifiChanged = true
                }
                "wifi.psk" -> {
                    pskSet = v.isNotEmpty()
                    wifiChanged = true
                }
                "lan.on" -> {
                    lanOn = v == "1"
                    logLine("LAN ${if (lanOn) "ON" else "OFF"}")
                }
                "location.cell" -> locationCell = v
                "thing.mobility" -> mobility = v
            }
        }
        logLine("CONFIG SET keys=${parsed.joinToString(",") { it.first }}")
        if (wifiChanged) applyWifi()
        return null
    }

    /** Off, connecting, then connected (or failed for an unknown SSID), like the firmware's 30 s retry loop but faster. */
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
                if (lanOn) logLine("LAN LISTEN port=$LAN_PORT mdns=_ossync._tcp host=os-6b40")
            } else {
                wifiState = "failed"
                logLine("WIFI FAILED ssid=$ssid (demo: only 'home', 'walkthru-field', 'CoffeeShop Guest' exist)")
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
        log.clear()
        logTotal = 0
        logLine("BOOT (demo reboot) pair=$pairMode pin_default=${if (pin == 123_456) 1 else 0}")
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

    private fun emitSample() {
        upSeconds += liveIntervalMs / 1000
        seq++
        buffered++
        liveValues.step()
        _live.tryEmit(liveJson().encodeToByteArray())
        _status.tryEmit(statusJson().encodeToByteArray())
    }

    private fun infoJson(): String =
        """{"proto":3,"fw":"$FIRMWARE","schema":"${template.schema}","cols":${template.datastreams.count { !it.isDerived }},""" +
            """"dict":"${template.dict}","thing":"$thing","dev":"$deviceId","boot":"$bootId","max_read":$maxRead}"""

    private fun statusJson(): String =
        """{"up_s":$upSeconds,"int_s":900,"buf":$buffered,"fin":$finalized,"drop":0,"err":0,"miss":0,""" +
            """"fail":0,"codec":"LZ4_RAW","utc":${if (utcAnchorNs != null) 1 else 0},"gen":$clockGen,"sd":1,""" +
            """"sd_kib":$SD_KIB,"sd_used_kib":${sdUsedKib()},"heap":180000,"part":1}"""

    private fun liveJson(): String =
        liveValues.json(seq, upSeconds * 1_000_000, utcAnchorNs?.let { it + upSeconds * 1_000_000_000L })

    private fun sdUsedKib(): Long = 34_176 + files.sumOf { it.bytes.size.toLong() } / 1024

    /** A little-endian reader over one request, past its opcode once [u8] took it. */
    private class Body(private val bytes: ByteArray) {
        private var pos = 0
        val remaining: Int get() = bytes.size - pos

        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = (u16().toLong() or (u16().toLong() shl 16)) and 0xFFFF_FFFFL
        fun i64(): Long = u32() or (u32() shl 32)
        fun rest(): ByteArray = bytes.copyOfRange(pos, bytes.size).also { pos = bytes.size }
    }

    companion object {
        /** The Bluetooth-like demo board. Both apps use these ids, so it is the same known device. */
        const val ADDRESS = "FA:KE:00:00:00:01"

        /** The same board seen as a LAN link. */
        const val LAN_ADDRESS = "FA:KE:00:00:00:02"

        /** Whether [address] names a demo board. */
        fun isDemo(address: String): Boolean = address == ADDRESS || address == LAN_ADDRESS

        /** The demo board for [address], the LAN variant for [LAN_ADDRESS]. */
        fun forAddress(address: String): DemoSensor {
            require(isDemo(address)) { "$address is not a demo address" }
            return DemoSensor(address = address, simulateLan = address == LAN_ADDRESS)
        }

        private const val FIRMWARE = "idf-cores3-parquet-v6.15 (demo)"
        private const val SD_KIB = 31_166_976L
        private const val LOG_RING = DeviceSession.LOG_RING_BYTES
        private const val LAN_PORT = 47390
        private val CELL = Regex("[0-9a-f]{15}")
        private val COUNTRY = Regex("[A-Z]{2}")

        /** The bundled CoreS3 dictionary, so the demo board resolves like a current CoreS3. */
        private const val CORES3_DICT = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"

        /** The bundled board template the demo board serves by default. */
        fun defaultTemplate(): BoardTemplate = checkNotNull(BundledCatalog.template(CORES3_DICT)) { "bundled template $CORES3_DICT is missing" }

        /** `PAR1` + random body + 4-byte footer length + `PAR1`. Not a real Parquet file, but passes the head and tail check. */
        fun demoParquet(rnd: Random, size: Int): ByteArray {
            require(size >= ParquetVerify.MIN_SIZE)
            val out = ByteArray(size)
            rnd.nextBytes(out)
            ParquetVerify.MAGIC.copyInto(out, 0)
            ParquetVerify.MAGIC.copyInto(out, size - 4)
            val footerLen = (size - 12).coerceAtLeast(0)
            for (i in 0 until 4) out[size - 8 + i] = (footerLen ushr (8 * i)).toByte()
            return out
        }
    }
}
