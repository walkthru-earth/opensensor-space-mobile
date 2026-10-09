package earth.walkthru.opensensor

import androidx.compose.runtime.snapshotFlow
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.opensensor.designsystem.nowEpochMillis
import earth.walkthru.opensensor.today.TodayArchive
import earth.walkthru.opensensor.today.TodayNotice
import earth.walkthru.opensensor.today.TodaySnapshot
import earth.walkthru.opensensor.today.TodaySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import earth.walkthru.aqsync.data.SyncProgress as RunProgress

/** What Today may ask of the Swift adapters. Each one is a closure the app already passes, plus [command]. */
class CompanionLinkActions(
    /** Sync the connected sensor. The app routes it to an open Wi-Fi session for the same sensor. */
    internal val syncFiles: (String) -> Unit,
    /** Automatic `SET_TIME` or confirmed `FLUSH` over this sensor's active connection. */
    internal val command: (String, SensorCommand) -> Unit,
    internal val connect: (String) -> Unit,
    internal val disconnect: (String) -> Unit,
)

/**
 * The iOS side of the Today port: [CompanionState], which `BluetoothCompanion` fills, read as a
 * [TodaySnapshot], and the verbs sent back through [actions]. [address] is the peripheral id Today
 * was opened for. Readings stay null until the sensor sent them. Smart Sync sends `SET_TIME` before
 * the file sync when the automatic policy requires it, as Android's repository does.
 */
internal class CompanionTodaySource(
    scope: CoroutineScope,
    private val state: CompanionState,
    private val address: String,
    archive: Flow<List<ArchiveRecord>>,
    todaySoFar: Flow<TodaySoFar?>,
    private val actions: CompanionLinkActions,
    private val stationFor: (String) -> String? = { null },
    private val nameFor: (String) -> String? = { null },
    private val now: () -> Long = { nowEpochMillis() },
) : TodaySource {
    /** One read of the companion state. Compose state, so [snapshotFlow] sees every change. */
    private data class Seen(
        val selectedId: String?,
        val connected: Boolean,
        val name: String?,
        val info: DeviceInfo?,
        val status: DeviceStatus?,
        val live: LiveSample?,
        val syncBusy: Boolean,
        val progress: SyncProgress?,
        val result: SyncResult?,
        val lanName: String?,
        val lanInfo: DeviceInfo?,
        val lanStatus: DeviceStatus?,
        val lanLive: LiveSample?,
        val lanStatusAt: Long?,
        val lanSession: LanSessionState?,
        val lanRun: LanRunState?,
    )

    /** [Seen] with the instants Today shows: when status and live arrived, when the sync ran. */
    private data class Stamped(
        val seen: Seen,
        val name: String?,
        val statusAt: Long?,
        val liveAt: Long?,
        val startedAt: Long?,
        val finishedAt: Long?,
    )

    private val stamped: Flow<Stamped> = snapshotFlow {
        val station = stationFor(address)
        val lanName = state.connectedLanForStation(station)
        val lanRun = lanName?.let(state.lanRuns::get)
            ?: station?.let { wanted ->
                state.lanRuns.values.filter { it.station == wanted }.maxByOrNull { it.startedAtEpochMs ?: Long.MIN_VALUE }
            }
        Seen(
            selectedId = state.selectedId,
            connected = state.connected,
            name = state.devices.firstOrNull { it.id == address }?.name ?: nameFor(address),
            info = state.info,
            status = state.status,
            live = state.live,
            syncBusy = state.syncBusy,
            progress = state.syncProgress,
            result = state.lastSyncResult,
            lanName = lanName,
            lanInfo = lanName?.let(state.lanInfo::get),
            lanStatus = lanName?.let(state.lanStatus::get),
            lanLive = lanName?.let(state.lanLive::get),
            lanStatusAt = lanName?.let(state.lanStatusAtEpochMs::get),
            lanSession = lanName?.let(state.lanSessions::get),
            lanRun = lanRun,
        )
    }.runningFold<Seen, Stamped?>(null) { previous, seen ->
        val at = now()
        val was = previous?.seen
        Stamped(
            seen = seen,
            // A new scan clears the nearby list, the name the user saw stays.
            name = seen.name ?: previous?.name,
            statusAt = if (seen.lanName != null) seen.lanStatusAt else seen.status?.let { if (was?.status == it) previous.statusAt else at },
            liveAt = (if (seen.lanName != null) seen.lanLive else seen.live)?.let {
                if (was?.lanName == seen.lanName && (if (seen.lanName != null) was?.lanLive else was?.live) == it) previous?.liveAt else at
            },
            startedAt = if ((seen.syncBusy || seen.lanSession?.busy == true) && was?.syncBusy != true && was?.lanSession?.busy != true) at else previous?.startedAt,
            finishedAt = when {
                seen.syncBusy || seen.result == null -> null
                was?.syncBusy == true || was?.result !== seen.result -> at
                else -> previous.finishedAt
            },
        )
    }.filterNotNull()

    override val snapshot: StateFlow<TodaySnapshot> = combine(stamped, archive, todaySoFar) { s, records, today ->
        val seen = s.seen
        // The one Bluetooth link may belong to another sensor now. This Today then shows its own as closed.
        val mine = seen.selectedId == address
        val overLan = seen.lanName != null && seen.lanSession?.connected == true && seen.lanInfo != null
        val connected = overLan || (mine && seen.connected)
        val station = if (overLan) seen.lanInfo.station else seen.info?.station?.takeIf { mine } ?: stationFor(address)
        TodaySnapshot(
            address = address,
            name = s.name,
            connection = when {
                connected -> ConnectionState.Ready(0)
                mine -> ConnectionState.Connecting
                else -> ConnectionState.Disconnected
            },
            transport = when {
                overLan -> TransportKind.LAN
                connected -> TransportKind.BLE
                else -> null
            },
            info = if (overLan) seen.lanInfo else seen.info?.takeIf { mine },
            status = if (overLan) seen.lanStatus else seen.status?.takeIf { mine },
            statusAtEpochMs = s.statusAt?.takeIf { connected },
            live = if (overLan) seen.lanLive else seen.live?.takeIf { mine },
            liveAtEpochMs = s.liveAt?.takeIf { connected },
            sync = when {
                overLan || (!mine && seen.lanRun != null) -> lanProgress(seen.lanSession, seen.lanRun)
                mine -> runProgress(seen, s)
                else -> RunProgress()
            },
            archive = if (station == null) TodayArchive() else TodayArchive.of(records.filter { it.station == station }),
            todaySoFar = today?.takeIf { connected },
        )
    }.stateIn(scope, SharingStarted.Eagerly, TodaySnapshot(address = address))

    private val local = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val notices: Flow<TodayNotice> = merge(state.today.notices, local)

    /** The one Bluetooth link is open to this sensor. Nothing is sent to another one. */
    private val linked: Boolean get() = state.connectedLanForStation(stationFor(address)) != null || (state.selectedId == address && state.connected)

    override fun sync(mode: ClockSyncMode, clockConfirmed: Boolean) {
        // A closed link shows Reconnect, so a stale tap has nothing to send.
        if (!linked) return
        if (state.today.busy || state.syncBusy || lanBusy) {
            local.tryEmit(TodayNotice.Busy)
            return
        }
        // SensorSync checks a fresh STATUS through the common clock policy before LIST on either
        // transport. Clock failure still permits preserving immutable original files.
        actions.syncFiles(address)
    }

    override fun setTime() = send(SensorCommand.SET_TIME)

    override fun flush() = send(SensorCommand.FLUSH)

    private fun send(command: SensorCommand) {
        if (!linked) return
        if (state.today.busy || state.syncBusy || lanBusy) {
            local.tryEmit(TodayNotice.Busy)
            return
        }
        actions.command(address, command)
    }

    override fun reconnect() = actions.connect(address)

    override fun disconnect() {
        if (linked) actions.disconnect(address)
    }

    private val lanBusy: Boolean get() = state.connectedLanForStation(stationFor(address))?.let { state.lanSessions[it]?.busy } == true

    private fun lanProgress(session: LanSessionState?, run: LanRunState?): RunProgress = RunProgress(
        running = run?.running == true,
        filesDone = session?.filesDone ?: 0,
        filesTotal = session?.filesTotal ?: 0,
        bytesDone = session?.bytesDone ?: 0,
        bytesTotal = session?.bytesTotal ?: 0,
        bytesPerSecond = session?.bytesPerSecond ?: 0.0,
        outcomes = fileFailures(run?.result, lanFailureCount(run)),
        startedAtEpochMs = run?.startedAtEpochMs,
        finishedAtEpochMs = run?.finishedAtEpochMs,
    )

    private fun lanFailureCount(run: LanRunState?): Int {
        val result = run?.result ?: return if (run?.interrupted == true) 1 else 0
        if (result.notSaved > 0) return result.notSaved
        return if (result.end == SyncEnd.COMPLETE || result.end == SyncEnd.FILES_NOT_SAVED) 0 else 1
    }

    private fun runProgress(seen: Seen, s: Stamped): RunProgress {
        val progress = seen.progress
        // iOS reports how many files were not saved, not which. The card shows the count only.
        val notSaved = seen.result?.notSaved ?: 0
        return RunProgress(
            running = seen.syncBusy,
            filesDone = progress?.filesDone ?: 0,
            filesTotal = progress?.filesTotal ?: 0,
            bytesDone = progress?.receivedBytes ?: 0,
            bytesTotal = progress?.bytesTotal ?: 0,
            bytesPerSecond = progress?.bytesPerSecond ?: 0.0,
            outcomes = fileFailures(seen.result, notSaved),
            startedAtEpochMs = s.startedAt,
            finishedAtEpochMs = s.finishedAt,
        )
    }

    private fun fileFailures(result: SyncResult?, count: Int): List<FileOutcome> {
        val known = result?.failures.orEmpty().map { FileOutcome.Failed(name = it.name, message = it.problem.name) }
        return known + List((count - known.size).coerceAtLeast(0)) { FileOutcome.Failed(name = "", message = "") }
    }
}
