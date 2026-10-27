package space.opensensor.today

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.opensensor.archive.ArchiveRecord
import space.opensensor.collection.SyncEndReason
import space.opensensor.data.SyncProgress
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.ThingStatus
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.Pm25NowCast
import space.opensensor.family.air.QcFlags
import space.opensensor.family.air.showsAirCards
import space.opensensor.family.air.showsBatchEstimate
import space.opensensor.settings.DetailLevel

/** How the phone archive places this device's readings in time, for the Smart Sync card. */
data class TodayArchive(
    val files: Int = 0,
    val estimatedRows: Long = 0,
    val unplacedRows: Long = 0,
) {
    companion object {
        /**
         * The summary of one thing's archive records, the rule Android's repository applies to its
         * Room rows: indexed telemetry files only, estimated when placed by an estimate alone,
         * unplaced when neither real nor estimated UTC exists.
         */
        fun of(records: List<ArchiveRecord>): TodayArchive {
            val files = records.filter { it.isIndexed && it.isTelemetry }
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
    /** Today (local calendar day) from the phone archive, once it has timed rows for this thing. */
    val todaySoFar: TodaySoFar? = null,
    /** A background worker or Collect all holds this device. The page waits, it never offers Reconnect. */
    val heldInBackground: Boolean = false,
) {
    /** The status every thing page reads, see [ThingStatus]. */
    val thing: ThingStatus get() = ThingStatus(connection, reconnecting != null, sync.running, heldInBackground)
}

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
}

/**
 * The port between the shared Today screen and a platform's live link. Android adapts
 * `DeviceFacade`, iOS adapts the companion state of `BluetoothCompanion` and
 * `LanDiscovery`. Transport stays behind it, the screen only reads [snapshot] and calls the verbs.
 */
interface TodaySource {
    val snapshot: StateFlow<TodaySnapshot>
    val notices: Flow<TodayNotice>

    /** Smart Sync. The sync engine sets the device clock automatically from this phone's UTC. */
    fun sync()

    /** Internal automatic `SET_TIME` with this phone UTC. No manual control exposes this action. */
    fun setTime()

    /** `FLUSH`, finalizing the buffered rows. Called only after the user confirmed it. */
    fun flush()

    /** Stops this phone's running sync. Files already verified stay synced. */
    fun stopSync()

    fun reconnect()
    fun disconnect()
}

/** The state Today draws: the snapshot, the detail level, and what follows from both. */
data class TodayUiState(
    val snapshot: TodaySnapshot = TodaySnapshot(),
    val level: DetailLevel = DetailLevel.SIMPLE,
    /** What this device can do, from `INFO` and LIVE (shared mapping, no schema strings in the UI). */
    val capabilities: DeviceCapabilities = DeviceCapabilities.from(snapshot.info),
    /** The PM2.5 NowCast of this thing from the phone archive (#93), null until it has the hours. */
    val nowCast: Pm25NowCast? = null,
    /** The [QcFlags] of the live sample (#91). */
    val liveFlags: Int = QcFlags.NONE,
) {

    /** The live sample read through the template and reviewed profile, null before both exist. */
    val reading: LiveReading? = capabilities.reading(snapshot.live)

    /** What the air hero shows, the NowCast when there is one, else the live sample, with its flags. */
    val airReading: NowReading get() = NowReading.of(reading, nowCast, liveFlags)

    /** Whether the live values can show yet, see [LiveSupport]. */
    val liveSupport: LiveSupport
        get() = when {
            snapshot.info == null -> LiveSupport.PENDING
            capabilities.thing == null -> LiveSupport.UNSUPPORTED
            else -> LiveSupport.READY
        }

    /** The particle reading (mood and PM2.5) applies to this device and sample. */
    val showsAirCards: Boolean get() = capabilities.showsAirCards(snapshot.live)

    /** The board opted in to the unvalidated batch estimate and its dictionary is reviewed. */
    val showsBatchEstimate: Boolean get() = capabilities.showsBatchEstimate(snapshot.config?.config?.sensor?.batchCandidate)

    /** The family presenter the app profile's `hero` names, see [HeroRegistry]. */
    val presenter: FamilyPresenter = HeroRegistry.presenterFor(capabilities.thing?.profile?.hero)

    /** The board's power columns, found by observed property. */
    val power: BoardPower = BoardPower.of(capabilities.thing)

    val cards: List<TodayCard> get() = todayCards(this)
}

/**
 * Whether Today can read LIVE. [PENDING] while `INFO` has not arrived, a quiet wait. [UNSUPPORTED]
 * only once `INFO` arrived with a dictionary the app has no reviewed profile for. [READY] otherwise.
 */
enum class LiveSupport { PENDING, UNSUPPORTED, READY }

/** The cards of Today, in screen order. Each one is a stable list key. */
enum class TodayCard(val key: String) {
    /** The hero slot, drawn by the [FamilyPresenter]. */
    NOW("now"),
    TODAY_SO_FAR("today"),
    ENVIRONMENT("environment"),
    SMART_SYNC("smart-sync"),
    CLOCK("clock"),
    BATCH_ESTIMATE("batch-estimate"),
    READINGS("readings"),
    PARTICLES("particles"),

    /** The thing's other primary and measured columns, for a family without its own cards. */
    METRICS("metrics"),
    BOARD("board"),
    STORAGE("storage"),
    DEVICE_INFO("info"),
}

/**
 * Which cards Today shows, the same on both platforms. Simple has the hero, today so far, Smart Sync
 * and the clock. Detailed adds the board, storage, the batch estimate and the device card. The
 * [FamilyPresenter] adds its own cards after today so far and after the batch estimate, so the air
 * family keeps its environment, readings and particle cards and any other thing gets one card of
 * its other readings. Conditional cards sit after the first one, so a level change never moves the
 * top of the list.
 */
fun todayCards(state: TodayUiState): List<TodayCard> = buildList {
    val presenter = state.presenter
    add(TodayCard.NOW)
    add(TodayCard.TODAY_SO_FAR)
    addAll(presenter.simpleCards(state))
    add(TodayCard.SMART_SYNC)
    add(TodayCard.CLOCK)
    if (state.level.detailed) {
        if (state.showsBatchEstimate) add(TodayCard.BATCH_ESTIMATE)
        addAll(presenter.detailedCards(state))
        if (presenter.boardTemperature(state) || state.power.battery != null) add(TodayCard.BOARD)
        add(TodayCard.STORAGE)
        if (state.snapshot.info != null) add(TodayCard.DEVICE_INFO)
    }
}

/**
 * Plain-language sync state, derived only from what the platform reports. A run that ended early
 * is [STOPPED] (the user, another action or the OS ended it) or [FAILED], never caught up.
 */
enum class SyncMood { RUNNING, CAUGHT_UP, SOME_FAILED, STOPPED, FAILED, READY, EMPTY }

fun syncMood(sync: SyncProgress, archive: TodayArchive): SyncMood {
    val reason = sync.endReason
    return when {
        sync.running -> SyncMood.RUNNING
        reason == SyncEndReason.STOPPED || reason == SyncEndReason.INTERRUPTED -> SyncMood.STOPPED
        reason != null && reason != SyncEndReason.COMPLETED -> SyncMood.FAILED
        sync.finishedAtEpochMs != null && sync.failures.isNotEmpty() -> SyncMood.SOME_FAILED
        sync.finishedAtEpochMs != null -> SyncMood.CAUGHT_UP
        archive.files > 0 -> SyncMood.READY
        else -> SyncMood.EMPTY
    }
}

/** The one line Today shows for a run that ended early. Each one says what to do next. */
enum class SyncEndLine {
    STOPPED,
    INTERRUPTED,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    PAIRING_REPAIR,

    /** Simple wording for both Wi-Fi token endings. Detailed names which one. */
    WIFI_ACCESS,
    TOKEN_ROTATED,
    TOKEN_REJECTED,
    DEVICE_BUSY,
    OUT_OF_RANGE,
    UPDATE_FIRMWARE,
    NO_SPACE,
    STORAGE_ERROR,
    LINK_LOST,
    OTHER,
}

/** The line for a [SyncMood.STOPPED] or [SyncMood.FAILED] ending, or null for any other mood. */
fun syncEndLine(sync: SyncProgress, level: DetailLevel): SyncEndLine? {
    if (sync.running) return null
    return when (sync.endReason) {
        null, SyncEndReason.COMPLETED -> null
        SyncEndReason.STOPPED -> SyncEndLine.STOPPED
        SyncEndReason.INTERRUPTED -> SyncEndLine.INTERRUPTED
        SyncEndReason.BLUETOOTH_OFF -> SyncEndLine.BLUETOOTH_OFF
        SyncEndReason.PERMISSION_MISSING -> SyncEndLine.PERMISSION_MISSING
        SyncEndReason.PAIRING_REPAIR -> SyncEndLine.PAIRING_REPAIR
        SyncEndReason.TOKEN_ROTATED -> if (level.detailed) SyncEndLine.TOKEN_ROTATED else SyncEndLine.WIFI_ACCESS
        SyncEndReason.TOKEN_REJECTED -> if (level.detailed) SyncEndLine.TOKEN_REJECTED else SyncEndLine.WIFI_ACCESS
        SyncEndReason.DEVICE_BUSY -> SyncEndLine.DEVICE_BUSY
        SyncEndReason.OUT_OF_RANGE -> SyncEndLine.OUT_OF_RANGE
        SyncEndReason.UPDATE_FIRMWARE -> SyncEndLine.UPDATE_FIRMWARE
        SyncEndReason.NO_SPACE -> SyncEndLine.NO_SPACE
        SyncEndReason.STORAGE_ERROR -> SyncEndLine.STORAGE_ERROR
        SyncEndReason.LINK_LOST -> SyncEndLine.LINK_LOST
        SyncEndReason.OTHER -> SyncEndLine.OTHER
    }
}
