package space.opensensor

import androidx.compose.runtime.snapshotFlow
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
import space.opensensor.archive.ArchiveRecord
import space.opensensor.collection.SyncEndReason
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.sync.FileOutcome
import space.opensensor.today.TodayArchive
import space.opensensor.today.TodayNotice
import space.opensensor.today.TodaySnapshot
import space.opensensor.today.TodaySource
import space.opensensor.data.SyncProgress as RunProgress

/** What Today may ask of the Swift adapters. Each one is a closure the app already passes, plus [command]. */
class CompanionLinkActions(
    /** Sync the connected sensor. The app routes it to an open Wi-Fi session for the same sensor. */
    internal val syncFiles: (String) -> Unit,
    /** A confirmed `FLUSH` over this sensor's active connection. */
    internal val command: (String, SensorCommand) -> Unit,
    internal val connect: (String) -> Unit,
    internal val disconnect: (String) -> Unit,
    /** Stops this phone's running sync of the sensor. Verified files stay synced. */
    internal val cancelSync: (String) -> Unit,
)

/**
 * The iOS side of the Today port: [CompanionState], which `BluetoothCompanion` fills, read as a
 * [TodaySnapshot], and the verbs sent back through [actions]. [address] is the peripheral id Today
 * was opened for. Readings stay null until the sensor sent them. The clock check runs once per
 * connection in `SensorSync`, never from Today.
 */
internal class CompanionTodaySource(
    scope: CoroutineScope,
    private val state: CompanionState,
    private val address: String,
    archive: Flow<List<ArchiveRecord>>,
    todaySoFar: Flow<TodaySoFar?>,
    private val actions: CompanionLinkActions,
    private val thingFor: (String) -> String? = { null },
    private val nameFor: (String) -> String? = { null },
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
        val statusAt: Long?,
        val liveAt: Long?,
        val startedAt: Long?,
        val finishedAt: Long?,
        val reconnecting: Boolean,
        val failed: Boolean,
        val lanName: String?,
        val lanInfo: DeviceInfo?,
        val lanStatus: DeviceStatus?,
        val lanLive: LiveSample?,
        val lanStatusAt: Long?,
        val lanLiveAt: Long?,
        val lanSession: LanSessionState?,
        val lanRun: LanRunState?,
        val heldInBackground: Boolean,
    )

    /** [Seen] with the name the user last saw, kept when a new scan clears the nearby list. */
    private data class Stamped(val seen: Seen, val name: String?)

    private val stamped: Flow<Stamped> = snapshotFlow {
        val thing = thingFor(address)
        val lanName = state.connectedLanForThing(thing)
        val lanRun = lanName?.let(state.lanRuns::get)
            ?: thing?.let { wanted ->
                state.lanRuns.values.filter { it.thing == wanted }.maxByOrNull { it.startedAtEpochMs ?: Long.MIN_VALUE }
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
            // The instants come from when each message arrived, so a recomposition never moves them.
            statusAt = state.statusAtEpochMs,
            liveAt = state.liveAtEpochMs,
            startedAt = state.syncStartedAtEpochMs,
            finishedAt = state.syncFinishedAtEpochMs,
            reconnecting = state.reconnectingId == address,
            failed = state.failedId == address,
            lanName = lanName,
            lanInfo = lanName?.let(state.lanInfo::get),
            lanStatus = lanName?.let(state.lanStatus::get),
            lanLive = lanName?.let(state.lanLive::get),
            lanStatusAt = lanName?.let(state.lanStatusAtEpochMs::get),
            lanLiveAt = lanName?.let(state.lanLiveAtEpochMs::get),
            lanSession = lanName?.let(state.lanSessions::get),
            lanRun = lanRun,
            heldInBackground = state.heldInBackground(address, thing),
        )
    }.runningFold<Seen, Stamped?>(null) { previous, seen ->
        // A new scan clears the nearby list, the name the user saw stays.
        Stamped(seen, seen.name ?: previous?.name)
    }.filterNotNull()

    override val snapshot: StateFlow<TodaySnapshot> = combine(stamped, archive, todaySoFar) { s, records, today ->
        val seen = s.seen
        // The one Bluetooth link may belong to another sensor now. This Today then shows its own as closed.
        val mine = seen.selectedId == address
        val overLan = seen.lanName != null && seen.lanSession?.connected == true && seen.lanInfo != null
        val connected = overLan || (mine && seen.connected)
        val thing = if (overLan) seen.lanInfo.thing else seen.info?.thing?.takeIf { mine } ?: thingFor(address)
        TodaySnapshot(
            address = address,
            name = s.name,
            connection = when {
                connected -> ConnectionState.Ready(0)
                mine || seen.reconnecting -> ConnectionState.Connecting
                seen.failed -> ConnectionState.Failed(PAIRING_REPAIR)
                else -> ConnectionState.Disconnected
            },
            transport = when {
                overLan -> TransportKind.LAN
                connected -> TransportKind.BLE
                else -> null
            },
            info = if (overLan) seen.lanInfo else seen.info?.takeIf { mine },
            status = if (overLan) seen.lanStatus else seen.status?.takeIf { mine },
            statusAtEpochMs = (if (overLan) seen.lanStatusAt else seen.statusAt?.takeIf { mine })?.takeIf { connected },
            live = if (overLan) seen.lanLive else seen.live?.takeIf { mine },
            liveAtEpochMs = (if (overLan) seen.lanLiveAt else seen.liveAt?.takeIf { mine })?.takeIf { connected },
            sync = when {
                overLan || (!mine && seen.lanRun != null) -> lanProgress(seen.lanSession, seen.lanRun)
                mine || seen.reconnecting -> runProgress(seen)
                else -> RunProgress()
            },
            archive = if (thing == null) TodayArchive() else TodayArchive.of(records.filter { it.thing == thing }),
            todaySoFar = today?.takeIf { connected },
            needsPairingRepair = !connected && !mine && seen.failed,
            heldInBackground = seen.heldInBackground,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TodaySnapshot(address = address))

    private val local = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val notices: Flow<TodayNotice> = merge(state.today.notices, local)

    /** The one Bluetooth link is open to this sensor. Nothing is sent to another one. */
    private val linked: Boolean get() = state.connectedLanForThing(thingFor(address)) != null || (state.selectedId == address && state.connected)

    override fun sync() {
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

    override fun flush() = send(SensorCommand.FLUSH)

    private fun send(command: SensorCommand) {
        if (!linked) return
        if (state.today.busy || state.syncBusy || lanBusy) {
            local.tryEmit(TodayNotice.Busy)
            return
        }
        actions.command(address, command)
    }

    override fun stopSync() = actions.cancelSync(address)

    override fun reconnect() = actions.connect(address)

    override fun disconnect() {
        if (linked) actions.disconnect(address)
    }

    private val lanBusy: Boolean get() = state.connectedLanForThing(thingFor(address))?.let { state.lanSessions[it]?.busy } == true

    private fun lanProgress(session: LanSessionState?, run: LanRunState?): RunProgress = RunProgress(
        running = run?.running == true,
        filesDone = session?.filesDone ?: 0,
        filesTotal = session?.filesTotal ?: 0,
        bytesDone = session?.bytesDone ?: 0,
        bytesTotal = session?.bytesTotal ?: 0,
        bytesPerSecond = session?.bytesPerSecond ?: 0.0,
        outcomes = fileFailures(run?.result, run?.result?.notSaved ?: 0),
        startedAtEpochMs = run?.startedAtEpochMs,
        finishedAtEpochMs = run?.finishedAtEpochMs,
        endReason = when {
            run == null || run.running -> null
            run.result != null -> run.result.endReason
            // The session closed under the run before it reported a result.
            run.interrupted -> SyncEndReason.LINK_LOST
            else -> null
        },
    )

    private fun runProgress(seen: Seen): RunProgress {
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
            startedAtEpochMs = seen.startedAt,
            finishedAtEpochMs = seen.finishedAt.takeIf { !seen.syncBusy && seen.result != null },
            endReason = seen.result?.endReason?.takeIf { !seen.syncBusy },
        )
    }

    private fun fileFailures(result: SyncResult?, count: Int): List<FileOutcome> {
        val known = result?.failures.orEmpty().map { FileOutcome.Failed(name = it.name, message = it.problem.name) }
        return known + List((count - known.size).coerceAtLeast(0)) { FileOutcome.Failed(name = "", message = "") }
    }

    private companion object {
        /** The technical reason behind a lost bond. Today shows its own repair words for it. */
        const val PAIRING_REPAIR = "BOND_MISSING"
    }
}
