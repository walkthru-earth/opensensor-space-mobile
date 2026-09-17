package earth.walkthru.aqsync.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.LogTailState
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.RawFrameRecord
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.AqJson
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.hexToBytes
import earth.walkthru.aqsync.data.protocol.toHex
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** One line of the raw-command result. */
data class RawResult(val hex: String, val decoded: String)

data class DebugUiState(
    val address: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
    val endpoint: String? = null,
    val payloadMax: Int? = null,
    val statusJsonPretty: String? = null,
    val statusAtEpochMs: Long? = null,
    val configJsonPretty: String? = null,
    val frames: List<RawFrameRecord> = emptyList(),
    val log: LogTailState = LogTailState.Idle,
    val rawBusy: Boolean = false,
    val rawResults: List<RawResult> = emptyList(),
    val rawError: String? = null,
    val advancedMode: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    settings: AppSettings,
) : ViewModel() {

    val events: SharedFlow<DeviceEvent> = repository.events

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val rawBusy = MutableStateFlow(false)
    private val rawResults = MutableStateFlow<List<RawResult>>(emptyList())
    private val rawError = MutableStateFlow<String?>(null)

    private val frames = repository.session.flatMapLatest { it?.frames?.records ?: flowOf(emptyList()) }

    private val base = combine(
        repository.session,
        repository.connectionState,
        repository.statusRaw,
        repository.status,
        repository.config,
        frames,
        repository.activeTransport,
    ) { values ->
        val session = values[0] as earth.walkthru.aqsync.data.ble.DeviceSession?
        val conn = values[1] as ConnectionState
        @Suppress("UNCHECKED_CAST")
        DebugUiState(
            address = session?.address,
            connection = conn,
            transport = values[6] as TransportKind?,
            endpoint = session?.transport?.endpoint,
            payloadMax = (conn as? ConnectionState.Ready)?.let { it.mtu - 3 },
            statusJsonPretty = (values[2] as String?)?.let(::prettyPrint),
            statusAtEpochMs = (values[3] as earth.walkthru.aqsync.data.TimedStatus?)?.receivedAtEpochMs,
            configJsonPretty = (values[4] as earth.walkthru.aqsync.data.ble.ConfigSnapshot?)?.let { prettyPrint(it.rawJson) },
            frames = (values[5] as List<RawFrameRecord>).asReversed(),
        )
    }

    val uiState: StateFlow<DebugUiState> = combine(base, repository.logTail, rawBusy, rawResults, rawError, settings.advancedMode) { values ->
        @Suppress("UNCHECKED_CAST")
        (values[0] as DebugUiState).copy(
            log = values[1] as LogTailState,
            rawBusy = values[2] as Boolean,
            rawResults = values[3] as List<RawResult>,
            rawError = values[4] as String?,
            advancedMode = values[5] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    private fun prettyPrint(json: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), AqJson.parseToJsonElement(json)) }.getOrDefault(json)

    fun refreshLog() = repository.refreshLogTail()
    fun requestStatus() = repository.requestStatus()
    fun clearFrames() {
        repository.session.value?.frames?.clear()
    }

    /** Hex in (whitespace allowed) → frames out. Runs through the same session mutex as everything else. */
    fun sendRaw(hex: String) {
        val session = repository.session.value ?: return
        val bytes = try {
            hex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            rawError.value = "not valid hex: ${e.message}"
            return
        }
        if (bytes.isEmpty() || bytes.size > 512) {
            rawError.value = "request must be 1..512 bytes"
            return
        }
        // The destructive requests need their confirmation dialog (AGENTS.md): REBOOT restarts the
        // logger, SET_CONFIG can rotate the token, forget bonds or disable the passkey. The raw
        // box is for looking at frames, not for bypassing the Device screen.
        val op = bytes[0].toInt() and 0xFF
        if (op == Opcode.REBOOT || op == Opcode.SET_CONFIG) {
            rawError.value = "${Opcode.name(op)} is not sent from here; use the Device screen, which asks for confirmation"
            return
        }
        viewModelScope.launch {
            rawBusy.value = true
            rawError.value = null
            try {
                val got = session.rawCommand(bytes)
                rawResults.value = got.map { (raw, frame) ->
                    RawResult(
                        // A TOKEN frame is a bearer secret: show the type byte only.
                        hex = if (frame is Frame.Token) raw.copyOf(1).toHex() + " <redacted>" else raw.toHex(),
                        decoded = frame?.let(::describe) ?: "undecodable (${raw.size} bytes)",
                    )
                }
                if (got.isEmpty()) rawError.value = "no response frames within the quiet period (STATUS 0x07 answers on the status channel only)"
            } catch (e: Exception) {
                rawError.value = e.message ?: e::class.simpleName
            } finally {
                rawBusy.value = false
            }
        }
    }

    private fun describe(f: Frame): String = when (f) {
        is Frame.Token -> "TOKEN port=${f.port} token=<redacted>"
        is Frame.Chunk -> "CHUNK handle=${f.handle} offset=${f.offset} len=${f.payload.size}"
        is Frame.Log -> "LOG ${f.bytes.size} bytes"
        is Frame.Config -> "CONFIG flags=${f.flags} ${f.json}"
        else -> f.toString()
    }

    companion object {
        fun typeName(type: Int): String = FrameType.name(type)
    }
}
