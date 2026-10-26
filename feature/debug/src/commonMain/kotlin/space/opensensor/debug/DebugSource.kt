package space.opensensor.debug

import kotlinx.coroutines.flow.Flow
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.LogTail
import space.opensensor.data.ble.RawFrameRecord
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.Frame

/** The link as the Debug screen describes it. [payloadMax] is null until a link reports its MTU. */
data class DebugLink(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
    val endpoint: String? = null,
    val payloadMax: Int? = null,
)

/** A JSON document from the sensor and when it arrived. */
data class DebugJson(val json: String, val atEpochMs: Long?)

/** How the archive placed its rows in time, for the Smart Sync diagnostics card. */
data class DebugArchiveTime(
    val files: Int = 0,
    val rows: Long = 0,
    val realTimeRows: Long = 0,
    val estimatedFiles: Int = 0,
    val estimatedRows: Long = 0,
    val unplacedFiles: Int = 0,
    val unplacedRows: Long = 0,
    val unplacedBoots: Int = 0,
)

/** One Smart Sync decision. */
data class DebugSyncStep(val atEpochMs: Long, val step: String, val detail: String)

/** Smart Sync diagnostics. A platform without the clock step reports none and the card is hidden. */
data class DebugSyncTrace(val archive: DebugArchiveTime = DebugArchiveTime(), val steps: List<DebugSyncStep> = emptyList())

/** The last `LOG_TAIL`. */
sealed interface DebugLog {
    data object Idle : DebugLog
    data object Loading : DebugLog
    data class Loaded(val tail: LogTail, val atEpochMs: Long) : DebugLog
    data class Failed(val message: String) : DebugLog
}

/** Why the raw box cannot send right now. */
enum class DebugBlock {
    /** Android background sync holds the sensor. */
    BACKGROUND_SYNC,

    /** A foreground sync holds the link, or no link could be lent. */
    LINK_BUSY,
}

/** A raw command could not reach the sensor for a [DebugBlock] reason. Nothing was sent. */
class DebugBlockedException(val block: DebugBlock) : Exception(block.name)

/**
 * What a platform lends the shared Debug screen. Android adapts its connection repository and its
 * long-lived session. iOS lends short links from its Swift transports, one per request, and keeps
 * one frame recorder per sensor so the history survives between them.
 *
 * Every frame flow already holds redacted records ([space.opensensor.data.ble.FrameRecorder]),
 * and [rawCommand] runs through [space.opensensor.data.ble.DeviceSession.rawCommand], which
 * refuses the denylist again.
 */
interface DebugSource {
    val link: Flow<DebugLink>
    val status: Flow<DebugJson?>
    val config: Flow<DebugJson?>
    val frames: Flow<List<RawFrameRecord>>
    val log: Flow<DebugLog>

    /** Null where the platform has no Smart Sync clock step yet. */
    val syncTrace: Flow<DebugSyncTrace?>

    /** True when the config is read on request rather than kept current by the connection. */
    val configOnRequest: Boolean

    /** Non-null when the raw box should refuse before sending anything. */
    fun rawBlock(): DebugBlock?

    /** Sends one checked request and collects its replies. May throw [DebugBlockedException]. */
    suspend fun rawCommand(request: ByteArray): List<Pair<ByteArray, Frame?>>

    fun requestStatus()
    fun refreshLog()
    fun refreshConfig() {}
    fun clearFrames()
}
