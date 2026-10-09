package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.data.sync.ClockQuality
import earth.walkthru.aqsync.settings.ClockSyncMode
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How the phone archive places this device's readings in time, for the Smart Sync card. */
data class TodayArchive(
    val files: Int = 0,
    val estimatedRows: Long = 0,
    val unplacedRows: Long = 0,
) {
    companion object {
        /**
         * The summary of one station's archive records, the rule Android's repository applies to its
         * Room rows: indexed telemetry files only, estimated when placed by an estimate alone,
         * unplaced when neither real nor estimated UTC exists.
         */
        fun of(records: List<ArchiveRecord>): TodayArchive {
            val files = records.filter { it.indexed && it.isTelemetry }
            return TodayArchive(
                files = files.size,
                estimatedRows = files.filter { it.hasEstimatedUtc }.sumOf { it.rows ?: 0L },
                unplacedRows = files.filter { !it.hasUtc && !it.hasEstimatedUtc }.sumOf { it.rows ?: 0L },
            )
        }
    }
}

/** A link that was Ready dropped on its own and the platform is trying to get it back. */
data class TodayReconnect(val attempt: Int, val maxAttempts: Int, val reason: String)

/**
 * Everything Today shows about one device, as the platform reports it. Missing values stay null,
 * the screen never invents a reading. [status] and [live] carry the instant they arrived, for the
 * freshness labels.
 */
data class TodaySnapshot(
    val address: String? = null,
    val name: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
    val info: DeviceInfo? = null,
    val status: DeviceStatus? = null,
    val statusAtEpochMs: Long? = null,
    val live: LiveSample? = null,
    val liveAtEpochMs: Long? = null,
    val sync: SyncProgress = SyncProgress(),
    val archive: TodayArchive = TodayArchive(),
    /** The last `GET_CONFIG` answer, for the board's batch estimate opt-in. */
    val config: ConfigSnapshot? = null,
    val reconnecting: TodayReconnect? = null,
    /** The link failed because the phone and the sensor disagree about the pairing. The platform decides. */
    val needsPairingRepair: Boolean = false,
    /** Today (local calendar day) from the phone archive, once it has timed rows for this station. */
    val todaySoFar: TodaySoFar? = null,
)

/** A device command Today can send. Each one is confirmed by the user first. */
enum class TodayCommand { SET_TIME, FLUSH }

/** One-off outcomes the platform reports back to Today. */
sealed interface TodayNotice {
    data object ClockSet : TodayNotice
    data class Finalized(val rows: Int, val files: Int) : TodayNotice
    data class Rejected(val command: TodayCommand) : TodayNotice
    data class Failed(val command: TodayCommand) : TodayNotice

    /** The link is doing something else, nothing was sent. */
    data object Busy : TodayNotice

    /** The platform's own Smart Sync found a clock to fix and needs consent first. */
    data class ClockConfirmationRequired(val quality: ClockQuality) : TodayNotice
}

/**
 * The port between the shared Today screen and a platform's live link. Android adapts
 * `DeviceConnectionRepository`, iOS adapts the companion state of `BluetoothCompanion` and
 * `LanDiscovery`. Transport stays behind it, the screen only reads [snapshot] and calls the verbs.
 */
interface TodaySource {
    val snapshot: StateFlow<TodaySnapshot>
    val notices: Flow<TodayNotice>

    /**
     * Smart Sync. [clockConfirmed] is true only after the user agreed to send this phone's time, or
     * when the mode is AUTOMATIC. The platform sends `SET_TIME` first when [mode] and the clock say so.
     */
    fun sync(mode: earth.walkthru.aqsync.data.sync.ClockSyncMode, clockConfirmed: Boolean)

    /** `SET_TIME` with this phone's clock. Called only after the user confirmed it. */
    fun setTime()

    /** `FLUSH`, finalizing the buffered rows. Called only after the user confirmed it. */
    fun flush()

    fun reconnect()
    fun disconnect()
}

/** The settings ClockSyncMode as the sync policy's own enum (same names). */
fun ClockSyncMode.toPolicy(): earth.walkthru.aqsync.data.sync.ClockSyncMode =
    earth.walkthru.aqsync.data.sync.ClockSyncMode.valueOf(name)

/** The state Today draws: the snapshot, the detail level, and what follows from both. */
data class TodayUiState(
    val snapshot: TodaySnapshot = TodaySnapshot(),
    val level: DetailLevel = DetailLevel.SIMPLE,
    val clockSyncMode: ClockSyncMode = ClockSyncMode.ASK,
) {
    /** What this device can do, from `INFO` (shared mapping, no schema strings in the UI). */
    val capabilities: DeviceCapabilities get() = DeviceCapabilities.from(snapshot.info)

    /** The particle reading (mood and PM2.5) applies to this device and sample. */
    val showsParticles: Boolean get() = capabilities.showsParticles(snapshot.live)

    /** The board opted in to the unvalidated batch estimate and its dictionary is reviewed. */
    val showsBatchEstimate: Boolean get() = capabilities.showsBatchEstimate(snapshot.config?.config?.sensor?.batchCandidate)

    val cards: List<TodayCard> get() = todayCards(this)
}

/** The cards of Today, in screen order. Each one is a stable list key. */
enum class TodayCard(val key: String) {
    NOW_AIR("now"),
    NOW_ENVIRONMENT("now"),
    TODAY_SO_FAR("today"),
    ENVIRONMENT("environment"),
    SMART_SYNC("smart-sync"),
    CLOCK("clock"),
    BATCH_ESTIMATE("batch-estimate"),
    READINGS("readings"),
    PARTICLES("particles"),
    BOARD("board"),
    STORAGE("storage"),
    DEVICE_INFO("info"),
}

/**
 * Which cards Today shows, the same on both platforms. Simple has the hero reading, today so far,
 * Smart Sync and the clock. Detailed adds the other measurements, board, storage, the batch estimate
 * and the device card. Conditional cards sit after the first one, so a level change never moves the
 * top of the list.
 */
fun todayCards(state: TodayUiState): List<TodayCard> = buildList {
    val particles = state.showsParticles
    add(if (particles) TodayCard.NOW_AIR else TodayCard.NOW_ENVIRONMENT)
    add(TodayCard.TODAY_SO_FAR)
    if (particles && state.capabilities.liveProfile?.temperature == TemperatureSource.AMBIENT) add(TodayCard.ENVIRONMENT)
    add(TodayCard.SMART_SYNC)
    add(TodayCard.CLOCK)
    if (state.level.detailed) {
        if (state.showsBatchEstimate) add(TodayCard.BATCH_ESTIMATE)
        if (particles) {
            add(TodayCard.READINGS)
            add(TodayCard.PARTICLES)
        }
        add(TodayCard.BOARD)
        add(TodayCard.STORAGE)
        if (state.snapshot.info != null) add(TodayCard.DEVICE_INFO)
    }
}

/** Plain-language sync state, derived only from what the platform reports. */
enum class SyncMood { RUNNING, CAUGHT_UP, SOME_FAILED, READY, EMPTY }

fun syncMood(sync: SyncProgress, archive: TodayArchive): SyncMood = when {
    sync.running -> SyncMood.RUNNING
    sync.finishedAtEpochMs != null && sync.failures.isNotEmpty() -> SyncMood.SOME_FAILED
    sync.finishedAtEpochMs != null -> SyncMood.CAUGHT_UP
    archive.files > 0 -> SyncMood.READY
    else -> SyncMood.EMPTY
}
