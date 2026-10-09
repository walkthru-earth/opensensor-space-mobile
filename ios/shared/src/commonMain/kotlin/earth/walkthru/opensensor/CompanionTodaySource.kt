package earth.walkthru.opensensor

import androidx.compose.runtime.snapshotFlow
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.sync.ClockSyncAction
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.SmartSyncPolicy
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import earth.walkthru.aqsync.data.SyncProgress as RunProgress

/** What Today may ask of the Swift adapters. Each one is a closure the app already passes, plus [command]. */
class CompanionLinkActions(
    /** Sync the connected sensor. The app routes it to an open Wi-Fi session for the same sensor. */
    internal val syncFiles: () -> Unit,
    /** Automatic `SET_TIME` or confirmed `FLUSH` over the Bluetooth connection. */
    internal val command: (SensorCommand) -> Unit,
    internal val connect: (String) -> Unit,
    internal val disconnect: () -> Unit,
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
        Seen(
            selectedId = state.selectedId,
            connected = state.connected,
            name = state.devices.firstOrNull { it.id == address }?.name,
            info = state.info,
            status = state.status,
            live = state.live,
            syncBusy = state.syncBusy,
            progress = state.syncProgress,
            result = state.lastSyncResult,
        )
    }.runningFold<Seen, Stamped?>(null) { previous, seen ->
        val at = now()
        val was = previous?.seen
        Stamped(
            seen = seen,
            // A new scan clears the nearby list, the name the user saw stays.
            name = seen.name ?: previous?.name,
            statusAt = seen.status?.let { if (was?.status == it) previous.statusAt else at },
            liveAt = seen.live?.let { if (was?.live == it) previous.liveAt else at },
            startedAt = if (seen.syncBusy && was?.syncBusy != true) at else previous?.startedAt,
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
        val connected = mine && seen.connected
        val station = seen.info?.station?.takeIf { mine }
        TodaySnapshot(
            address = address,
            name = s.name,
            connection = when {
                connected -> ConnectionState.Ready(0)
                mine -> ConnectionState.Connecting
                else -> ConnectionState.Disconnected
            },
            transport = if (connected) TransportKind.BLE else null,
            info = seen.info?.takeIf { mine },
            status = seen.status?.takeIf { mine },
            statusAtEpochMs = s.statusAt?.takeIf { mine },
            live = seen.live?.takeIf { mine },
            liveAtEpochMs = s.liveAt?.takeIf { mine },
            sync = if (mine) runProgress(seen, s) else RunProgress(),
            archive = if (station == null) TodayArchive() else TodayArchive.of(records.filter { it.station == station }),
            todaySoFar = today?.takeIf { mine },
        )
    }.stateIn(scope, SharingStarted.Eagerly, TodaySnapshot(address = address))

    private val local = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A Smart Sync waits for its `SET_TIME`. Read and written on the main thread only. */
    private var syncAfterClock = false

    override val notices: Flow<TodayNotice> = merge(
        state.today.notices.onEach {
            if (syncAfterClock) {
                syncAfterClock = false
                // Preserve original files even when the automatic clock refresh failed.
                if (linked) actions.syncFiles()
            }
        },
        local,
    )

    /** The one Bluetooth link is open to this sensor. Nothing is sent to another one. */
    private val linked: Boolean get() = state.selectedId == address && state.connected

    override fun sync(mode: ClockSyncMode, clockConfirmed: Boolean) {
        // A closed link shows Reconnect, so a stale tap has nothing to send.
        if (!linked) return
        if (state.today.busy || state.syncBusy) {
            local.tryEmit(TodayNotice.Busy)
            return
        }
        val decision = SmartSyncPolicy.decide(state.status, ClockSyncMode.AUTOMATIC)
        when (decision.action) {
            ClockSyncAction.CONTINUE -> actions.syncFiles()
            ClockSyncAction.SET_TIME, ClockSyncAction.ASK_USER -> clockThenSync()
        }
    }

    private fun clockThenSync() {
        syncAfterClock = true
        actions.command(SensorCommand.SET_TIME)
    }

    override fun setTime() = send(SensorCommand.SET_TIME)

    override fun flush() = send(SensorCommand.FLUSH)

    private fun send(command: SensorCommand) {
        if (!linked) return
        if (state.today.busy || state.syncBusy) {
            local.tryEmit(TodayNotice.Busy)
            return
        }
        actions.command(command)
    }

    override fun reconnect() = actions.connect(address)

    override fun disconnect() {
        if (state.selectedId == address) actions.disconnect()
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
            // The iOS runner does not report a byte total, so the card's bar runs without one.
            bytesTotal = 0,
            bytesPerSecond = progress?.bytesPerSecond ?: 0.0,
            outcomes = List(notSaved) { FileOutcome.Failed(name = "", message = "") },
            startedAtEpochMs = s.startedAt,
            finishedAtEpochMs = s.finishedAt,
        )
    }
}
