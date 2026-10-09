package earth.walkthru.opensensor.debug

import earth.walkthru.aqsync.data.ble.FrameRedaction
import earth.walkthru.aqsync.data.ble.RawCommandOutcome
import earth.walkthru.aqsync.data.ble.RawCommandSender
import earth.walkthru.aqsync.data.ble.RawFrameRecord
import earth.walkthru.aqsync.data.ble.RawReply
import earth.walkthru.aqsync.data.protocol.AqJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Why the raw command box refused or got nothing. The screen maps each to a string resource. */
sealed interface RawError {
    data object BackgroundOwns : RawError
    data object LinkBusy : RawError
    data object InvalidHex : RawError
    data object BadLength : RawError

    /** [opcode] needs the Device screen and its confirmation. */
    data class NeedsConfirmation(val opcode: String) : RawError
    data object NoResponse : RawError

    /** Transport or protocol detail, shown as is at Developer level. */
    data class Failed(val detail: String) : RawError
}

data class DebugUiState(
    val link: DebugLink = DebugLink(),
    val statusJsonPretty: String? = null,
    val statusAtEpochMs: Long? = null,
    val configJsonPretty: String? = null,
    val configOnRequest: Boolean = false,
    /** Newest first. */
    val frames: List<RawFrameRecord> = emptyList(),
    val log: DebugLog = DebugLog.Idle,
    val rawBusy: Boolean = false,
    val rawResults: List<RawReply> = emptyList(),
    val rawError: RawError? = null,
    val syncTrace: DebugSyncTrace? = null,
)

/**
 * State and actions behind [DebugScreen], free of Android and Hilt (the SettingsController
 * pattern). The raw box checks and renders through [RawCommandSender], so the denylist and the
 * redaction are the same on Android and iOS. Status and config JSON pass through
 * [FrameRedaction.scrubSecrets] before they are shown.
 */
class DebugController(private val scope: CoroutineScope, private val source: DebugSource) {
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val raw = MutableStateFlow(RawPanel())
    private val sender = RawCommandSender { source.rawCommand(it) }

    private data class RawPanel(val busy: Boolean = false, val results: List<RawReply> = emptyList(), val error: RawError? = null)

    private val base = combine(source.link, source.status, source.config, source.frames) { link, status, config, frames ->
        DebugUiState(
            link = link,
            statusJsonPretty = status?.json?.let(::show),
            statusAtEpochMs = status?.atEpochMs,
            configJsonPretty = config?.json?.let(::show),
            configOnRequest = source.configOnRequest,
            frames = frames.asReversed(),
        )
    }

    val state: StateFlow<DebugUiState> = combine(base, source.log, source.syncTrace, raw) { s, log, trace, panel ->
        s.copy(log = log, syncTrace = trace, rawBusy = panel.busy, rawResults = panel.results, rawError = panel.error)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), DebugUiState(configOnRequest = source.configOnRequest))

    fun requestStatus() = source.requestStatus()
    fun refreshLog() = source.refreshLog()
    fun refreshConfig() = source.refreshConfig()
    fun clearFrames() = source.clearFrames()

    /** Hex in (whitespace allowed) and redacted frames out. One request at a time. */
    fun sendRaw(hex: String) {
        if (raw.value.busy) return
        when (source.rawBlock()) {
            DebugBlock.BACKGROUND_SYNC -> return fail(RawError.BackgroundOwns)
            DebugBlock.LINK_BUSY -> return fail(RawError.LinkBusy)
            null -> Unit
        }
        raw.value = raw.value.copy(busy = true, error = null)
        scope.launch {
            val outcome = try {
                sender.send(hex)
            } catch (e: DebugBlockedException) {
                RawCommandOutcome.Failed(e.block.name)
            }
            raw.value = when (outcome) {
                is RawCommandOutcome.Replies -> RawPanel(results = outcome.replies)
                RawCommandOutcome.InvalidHex -> raw.value.copy(busy = false, error = RawError.InvalidHex)
                RawCommandOutcome.BadLength -> raw.value.copy(busy = false, error = RawError.BadLength)
                is RawCommandOutcome.Refused -> raw.value.copy(busy = false, error = RawError.NeedsConfirmation(outcome.opcode))
                RawCommandOutcome.NoResponse -> RawPanel(error = RawError.NoResponse)
                is RawCommandOutcome.Failed -> raw.value.copy(busy = false, error = failure(outcome.detail))
            }
        }
    }

    private fun failure(detail: String): RawError = when (detail) {
        DebugBlock.BACKGROUND_SYNC.name -> RawError.BackgroundOwns
        DebugBlock.LINK_BUSY.name -> RawError.LinkBusy
        else -> RawError.Failed(detail)
    }

    private fun fail(error: RawError) {
        raw.value = raw.value.copy(error = error)
    }

    private fun show(json: String): String {
        val clean = FrameRedaction.scrubSecrets(json)
        return runCatching { pretty.encodeToString(JsonElement.serializer(), AqJson.parseToJsonElement(clean)) }.getOrDefault(clean)
    }
}
