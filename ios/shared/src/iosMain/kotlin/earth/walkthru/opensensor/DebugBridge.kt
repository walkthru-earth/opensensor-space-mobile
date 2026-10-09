package earth.walkthru.opensensor

import androidx.compose.runtime.snapshotFlow
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.FrameRecorderRegistry
import earth.walkthru.aqsync.data.ble.RawFrameRecord
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.opensensor.debug.DebugBlock
import earth.walkthru.opensensor.debug.DebugBlockedException
import earth.walkthru.opensensor.debug.DebugJson
import earth.walkthru.opensensor.debug.DebugLink
import earth.walkthru.opensensor.debug.DebugLog
import earth.walkthru.opensensor.debug.DebugSource
import earth.walkthru.opensensor.debug.DebugSyncTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * One redacting frame recorder per sensor for the whole process. Every sync and every debug
 * request records into it, so the Debug screen shows the history across the short links iOS lends.
 */
internal val debugFrames = FrameRecorderRegistry()

/**
 * The iOS side of the shared Debug screen. Swift owns the socket and the peripheral, so each
 * request borrows a fresh [SensorLink] through [openLink] (null while a sync holds the link) and
 * closes it when done, which hands the link back to Swift. Status comes from the Bluetooth status
 * notifications [CompanionState] already receives. The config and the log are read on request.
 */
internal class IosDebugSource(
    private val address: String,
    private val state: CompanionState,
    private val openLink: (String) -> SensorLink?,
    private val scope: CoroutineScope,
) : DebugSource {
    private val recorder = debugFrames.recorderFor(address)
    private val lent = Mutex()
    private val lastKind = MutableStateFlow<Pair<TransportKind, Int>?>(null)
    private val configFlow = MutableStateFlow<DebugJson?>(null)
    private val logFlow = MutableStateFlow<DebugLog>(DebugLog.Idle)

    private fun lanName(): String? = state.lanInfo.entries.firstOrNull { (name, info) ->
        info.dev?.equals(address, ignoreCase = true) == true && state.lanSessions[name]?.connected == true
    }?.key

    override val link: Flow<DebugLink> = combine(
        snapshotFlow { (state.connected && state.info?.dev?.equals(address, ignoreCase = true) == true) to lanName() },
        lastKind,
    ) { (ble, lan), kind ->
        val transport = when {
            lan != null -> TransportKind.LAN
            ble -> TransportKind.BLE
            else -> null
        }
        val mtu = kind?.takeIf { it.first == transport }?.second
        DebugLink(
            connection = if (transport != null) ConnectionState.Ready(mtu ?: 0) else ConnectionState.Disconnected,
            transport = transport,
            endpoint = address,
            payloadMax = mtu?.let { it - 3 },
        )
    }

    override val status: Flow<DebugJson?> = snapshotFlow {
        val lan = lanName()
        if (lan != null) {
            state.lanStatusRaw[lan]?.let { DebugJson(it, state.lanStatusAtEpochMs[lan]) }
        } else if (state.info?.dev?.equals(address, ignoreCase = true) == true) {
            state.statusRaw?.let { DebugJson(it, state.statusAtEpochMs) }
        } else {
            null
        }
    }
    override val config: Flow<DebugJson?> = configFlow
    override val frames: Flow<List<RawFrameRecord>> = recorder.records
    override val log: Flow<DebugLog> = logFlow
    override val syncTrace: Flow<DebugSyncTrace?> = IosSyncDiagnostics.forDevice(address)
    override val configOnRequest: Boolean = true

    override fun rawBlock(): DebugBlock? = null

    override suspend fun rawCommand(request: ByteArray): List<Pair<ByteArray, Frame?>> = borrow { it.rawCommand(request) }

    override fun requestStatus() {
        scope.launch { runCatching { borrow { it.requestStatus() } } }
    }

    override fun refreshLog() {
        if (logFlow.value == DebugLog.Loading) return
        logFlow.value = DebugLog.Loading
        scope.launch {
            logFlow.value = try {
                DebugLog.Loaded(borrow { it.logTail() }, now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    override fun refreshConfig() {
        scope.launch {
            runCatching { borrow { it.getConfig() } }.onSuccess { configFlow.value = DebugJson(it.rawJson, it.atEpochMs) }
        }
    }

    override fun clearFrames() = recorder.clear()

    /** One request at a time over a lent link, closed afterwards so Swift gets it back. */
    private suspend fun <T> borrow(block: suspend (DeviceSession) -> T): T = lent.withLock {
        val link = lend() ?: throw DebugBlockedException(DebugBlock.LINK_BUSY)
        try {
            lastKind.value = link.transport.kind to link.transport.mtu
            block(DeviceSession(link.transport, recorder))
        } finally {
            link.linkClosed()
        }
    }

    /**
     * Swift may still be taking back the previous request's link, or finishing a queued write, so a
     * refusal is retried for about a second before the screen says the link is busy.
     */
    private suspend fun lend(): SensorLink? {
        repeat(LEND_ATTEMPTS) { attempt ->
            withContext(Dispatchers.Main) { openLink(address) }?.let { return it }
            if (attempt < LEND_ATTEMPTS - 1) delay(LEND_RETRY_MS)
        }
        return null
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val LEND_ATTEMPTS = 8
        const val LEND_RETRY_MS = 125L
    }
}
