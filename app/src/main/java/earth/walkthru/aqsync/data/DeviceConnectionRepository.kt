package earth.walkthru.aqsync.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.background.CompanionUnlinker
import earth.walkthru.aqsync.data.background.SyncForegroundService
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.InvalidConfigException
import earth.walkthru.aqsync.data.ble.LogTail
import earth.walkthru.aqsync.data.ble.NotAllowedOnLinkException
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.ble.WifiUnavailableException
import earth.walkthru.aqsync.data.coroutines.ApplicationScope
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.coroutines.MonotonicTime
import earth.walkthru.aqsync.data.coroutines.WallClock
import earth.walkthru.aqsync.data.coroutines.suspendRunCatching
import earth.walkthru.aqsync.data.history.HistoryRefresher
import earth.walkthru.aqsync.data.lan.LanConnectException
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.lan.LanTargets
import earth.walkthru.aqsync.data.lan.LanTokenPolicy
import earth.walkthru.aqsync.data.lan.WifiLeases
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.StationEpoch
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.local.security.LanCredentialStore
import earth.walkthru.aqsync.data.protocol.AqUuids
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.hexToBytes
import earth.walkthru.aqsync.data.protocol.toHex
import earth.walkthru.aqsync.data.sync.ArchivedIdentity
import earth.walkthru.aqsync.data.sync.ClockSyncAction
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.data.sync.RateMeter
import earth.walkthru.aqsync.data.sync.ReadWindow
import earth.walkthru.aqsync.data.sync.SmartSyncPolicy
import earth.walkthru.aqsync.data.sync.SyncEndCause
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.data.sync.SyncPlan
import earth.walkthru.aqsync.data.sync.SyncPlanner
import earth.walkthru.aqsync.data.sync.SyncResumePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** A `live` sample with the wall-clock instant it arrived, for the freshness indicator. */
data class TimedLive(val sample: LiveSample, val receivedAtEpochMs: Long)

data class TimedStatus(val status: DeviceStatus, val receivedAtEpochMs: Long)

data class ArchiveTimeSummary(
    val files: Int = 0,
    val rows: Long = 0,
    val realTimeRows: Long = 0,
    val estimatedFiles: Int = 0,
    val estimatedRows: Long = 0,
    val unplacedFiles: Int = 0,
    val unplacedRows: Long = 0,
    val unplacedBoots: Int = 0,
)

data class SyncDiagnostic(
    val atEpochMs: Long,
    val step: String,
    val detail: String,
)

/** Result of the last `LOG_TAIL`, for Advanced → Debug. */
sealed interface LogTailState {
    data object Idle : LogTailState
    data object Loading : LogTailState
    data class Loaded(val tail: LogTail, val atEpochMs: Long) : LogTailState
    data class Failed(val message: String) : LogTailState
}

/** How the repository should pick a transport for a connect. */
enum class TransportPreference { AUTO, BLE_ONLY }

/** A link that was Ready dropped on its own; the repository is trying to get it back. */
data class ReconnectState(val attempt: Int, val maxAttempts: Int, val reason: String)

/**
 * Owns one device connection and everything derived from it. Created only by DeviceConnectionManager.
 *
 * Process-scoped (manager-owned with its own [CoroutineScope]) so a download keeps running
 * through screen rotation and navigation; ViewModels only observe and forward user intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transportFactory: TransportSource,
    private val knownDeviceDao: KnownDeviceDao,
    private val lanCredentials: LanCredentialStore,
    private val archivedFileDao: ArchivedFileDao,
    private val archive: FileArchive,
    private val syncEngine: SyncEngine,
    private val lanDiscoveryState: LanTargets,
    private val lanWifiLock: WifiLeases,
    private val historyDeriver: HistoryRefresher,
    private val companionPresence: CompanionUnlinker,
    private val collectionBudget: CollectionBudget,
    private val identityClaims: DeviceIdentityClaims,
    private val stationEpochDao: StationEpochDao,
    @ApplicationScope applicationScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
    private val monotonic: MonotonicTime,
) {
    /**
     * Child of the process scope: one failed child never cancels its siblings, and cancelling the
     * application scope (tests) ends every job this controller started.
     */
    private val scope = CoroutineScope(applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]) + io)
    private val tokenPolicy = LanTokenPolicy()
    private var identityLease: AutoCloseable? = null

    private val _session = MutableStateFlow<DeviceSession?>(null)
    private val identityReady = MutableStateFlow(false)
    private val connectionFailure = MutableStateFlow<String?>(null)

    @Volatile private var closing = false
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = combine(
        _session.flatMapLatest { it?.connectionState ?: flowOf(ConnectionState.Disconnected) },
        identityReady,
        connectionFailure,
    ) { state, verified, failure ->
        if (failure != null) {
            ConnectionState.Failed(failure)
        } else if (state.isReady && !verified) {
            ConnectionState.Authenticating
        } else {
            state
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /**
     * Which pipe the current session uses, for the "Bluetooth" / "Wi-Fi LAN" chip on every device
     * screen. The single source for it: the screens used to derive the kind from the [session]
     * object inside their own wide `combine`s, so the chip depended on a `DeviceSession` identity
     * change reaching each ViewModel. This flow is re-evaluated on every session swap *and* on every
     * connection-state change of that session (the LAN probe in [tryLan] is reused as the session
     * when it succeeds; a reconnect may happen while no screen is subscribed), then de-duplicated.
     */
    val activeTransport: StateFlow<TransportKind?> = _session
        .flatMapLatest { s -> if (s == null) flowOf(null) else s.connectionState.map { s.transport.kind } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _config = MutableStateFlow<ConfigSnapshot?>(null)
    val config: StateFlow<ConfigSnapshot?> = _config.asStateFlow()

    private val _wifiScan = MutableStateFlow<WifiScanState>(WifiScanState.Idle)
    val wifiScan: StateFlow<WifiScanState> = _wifiScan.asStateFlow()

    private val _logTail = MutableStateFlow<LogTailState>(LogTailState.Idle)
    val logTail: StateFlow<LogTailState> = _logTail.asStateFlow()

    /**
     * Operation gate. [_busy] is set while a config write, reboot or token fetch is in flight, and
     * [_backgroundOwner] while a worker owns the device. Both change only under `synchronized(this)`
     * through [tryBeginCommand] / [tryBeginBackground], so a check and its set are one step and two
     * overlapping commands can never both run or clear each other's flag.
     */
    private val _busy = MutableStateFlow(false)
    private val _backgroundOwner = MutableStateFlow<String?>(null)
    val backgroundOwner: StateFlow<String?> = _backgroundOwner.asStateFlow()
    val busy: StateFlow<Boolean> = combine(_busy, backgroundOwner) { command, owner -> command || owner != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Non-null while a lost link is being re-established automatically (see [watchLink]). */
    private val _reconnecting = MutableStateFlow<ReconnectState?>(null)
    val reconnecting: StateFlow<ReconnectState?> = _reconnecting.asStateFlow()

    // ------------------------------------------------------------ LAN discovery

    val lanDevices get() = lanDiscoveryState.devices
    val lanDiscoveryError get() = lanDiscoveryState.error

    private var boundAddress: String? = null
    internal fun bind(address: String) {
        check(boundAddress == null || boundAddress == address)
        boundAddress = address
    }

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _status = MutableStateFlow<TimedStatus?>(null)
    val status: StateFlow<TimedStatus?> = _status.asStateFlow()

    /** The latest `status` JSON exactly as received (unknown keys included), for Advanced → Debug. */
    private val _statusRaw = MutableStateFlow<String?>(null)
    val statusRaw: StateFlow<String?> = _statusRaw.asStateFlow()

    private val _live = MutableStateFlow<TimedLive?>(null)
    val live: StateFlow<TimedLive?> = _live.asStateFlow()

    private val _manifest = MutableStateFlow<ManifestState>(ManifestState.Idle)
    val manifest: StateFlow<ManifestState> = _manifest.asStateFlow()

    private val _sync = MutableStateFlow(SyncProgress())
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()

    private val _archiveTimeSummary = MutableStateFlow(ArchiveTimeSummary())
    val archiveTimeSummary: StateFlow<ArchiveTimeSummary> = _archiveTimeSummary.asStateFlow()

    private val _syncDiagnostics = MutableStateFlow<List<SyncDiagnostic>>(emptyList())
    val syncDiagnostics: StateFlow<List<SyncDiagnostic>> = _syncDiagnostics.asStateFlow()

    /**
     * True while the process must stay out of the background firewall chain: a transfer is running,
     * or a link that was carrying one is being re-established. Drives the dataSync foreground service.
     */
    val foregroundNeeded: StateFlow<Boolean> = combine(_sync, _reconnecting) { s, r -> s.running || r != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    /** Address of the device we are connected to / connecting to. */
    val currentAddress: String? get() = _session.value?.address
    val currentDisplayName: String? get() = _session.value?.transport?.displayName
    val isFake: Boolean get() = _session.value?.transport?.let(transportFactory::isFake) == true

    /**
     * Manifest joined with the archive: what is synced, what is missing, what is mid-transfer.
     * Recomputed whenever the manifest, the archive table or the running transfer changes.
     */
    val plan: StateFlow<SyncPlan?> = combine(_manifest, _info, archivedFileDao.observeAll(), _sync) { m, info, archived, sync ->
        val station = info?.station ?: return@combine null
        // The last successful listing, kept through a refresh or its failure (see ManifestState).
        val loaded = m.last ?: return@combine null
        val ids = archived.map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
        val base = SyncPlanner.plan(station, loaded.files, ids, archive.partialLengths(station))
        overlayProgress(base, sync)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun overlayProgress(plan: SyncPlan, sync: SyncProgress): SyncPlan {
        if (!sync.running && sync.outcomes.isEmpty()) return plan
        val failures = sync.failures.associateBy { it.name }
        val entries = plan.entries.map { e ->
            val cur = sync.current
            when {
                sync.running && cur != null && cur.name == e.name && e.state != FileSyncState.SYNCED ->
                    e.copy(state = FileSyncState.SYNCING, partialBytes = cur.bytesDone)
                e.state != FileSyncState.SYNCED && failures[e.name] != null -> {
                    val f = failures.getValue(e.name)
                    val msg = when (f) {
                        is FileOutcome.Mismatch -> f.reason
                        is FileOutcome.DeviceError -> f.message
                        is FileOutcome.Failed -> f.message
                        is FileOutcome.Synced -> null
                    }
                    e.copy(state = if (f is FileOutcome.Mismatch) FileSyncState.MISMATCH else e.state, detail = msg)
                }
                else -> e
            }
        }
        return plan.copy(entries = entries)
    }

    private var notificationJob: Job? = null
    private var syncJob: Job? = null
    private var connectJob: Job? = null
    private val connectMutex = Mutex()
    private val connectionIntent = ConnectionIntent()

    // ------------------------------------------------------------------ connect

    /**
     * Connect to [address] (or the fake device when [fake]). Safe to call while connected to the same peer.
     *
     * Transport choice (contract, "LAN transport"): prefer LAN when the device's station is currently
     * discovered on the network *and* a token is stored, else BLE; if the LAN connect fails for any
     * reason fall back to BLE and say so. [preference] = [TransportPreference.BLE_ONLY] skips LAN
     * (needed for `GET_TOKEN`, which is BLE-only by contract).
     */
    fun connect(address: String, name: String?, fake: Boolean = false, preference: TransportPreference = TransportPreference.AUTO) {
        check(boundAddress == null || boundAddress == address) { "controller belongs to another device" }
        val claim = connectionIntent.claim()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            connectMutex.withLock {
                if (!connectionIntent.owns(claim)) return@withLock
                cancelReconnect()
                connectLocked(address, name, fake, preference, claim)
                if (!connectionIntent.owns(claim)) closeSessionLocked()
            }
        }
        synchronized(this) {
            connectJob?.cancel()
            connectJob = job
        }
        job.invokeOnCompletion { synchronized(this) { if (connectJob === job) connectJob = null } }
        job.start()
    }

    private fun cancelPendingConnect() {
        synchronized(this) {
            connectJob?.cancel()
            connectJob = null
        }
    }

    /**
     * Body of [connect]; true when a session is Ready afterwards. Caller holds [connectMutex].
     * [listAfter] = false skips the background `LIST` when the caller lists itself and waits.
     */
    private suspend fun connectLocked(address: String, name: String?, fake: Boolean, preference: TransportPreference, claim: Long, listAfter: Boolean = true): Boolean {
        if (backgroundOwner.value != null) {
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.BackgroundOwnsConnect))
            return false
        }
        val existing = _session.value
        if (existing != null && existing.address == address && existing.connectionState.value.isReady) {
            // A repeated Connect is a new explicit intent even when it can reuse the pipe. Give
            // the existing link watcher the new claim so future loss still reconnects it.
            watchLink(existing, address, name, fake, claim)
            return true
        }
        closeSessionLocked()
        resetDerivedState()

        knownDeviceDao.insertIfAbsent(
            KnownDevice(address, name, null, null, null, clock.nowEpochMs(), null)
        )
        val known = knownDeviceDao.get(address)

        var transport: AqTransport? = null
        if (fake) {
            transport = transportFactory.fake(address)
        } else if (preference == TransportPreference.AUTO) {
            transport = tryLan(address, name ?: known?.name, known)
        }
        if (transport == null) {
            transport = transportFactory.ble(address)
        }
        val opened = try {
            openSession(transport, address, name, fake, claim = claim)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { closeSessionLocked() }
            throw e
        }
        if (!opened) {
            closeSessionLocked()
            return false
        }
        if (listAfter) refreshManifest()
        return true
    }

    /**
     * Attempt a LAN session; null when not applicable or when it failed (the caller falls back to
     * BLE). The probe session is removed from the slot and its transport closed on every failure
     * path, including an unexpected exception and cancellation.
     */
    private suspend fun tryLan(address: String, name: String?, known: KnownDevice?): AqTransport? {
        val station = known?.station ?: return null
        val seen = lanDevices.value[station] ?: return null
        val host = seen.host ?: return null
        val port = TransportSelector.lanPort(known.hasLanToken, known.deviceId, seen.dev, seen.port, known.lanPort) ?: return null
        val token = lanTokenBytes(address) ?: return null
        val transport = transportFactory.lan(address, name, host, port, token)
        // Put the LAN attempt in the session slot so the UI shows "Authenticating on the LAN…".
        val probe = DeviceSession(transport).also { it.staleFrameLogger = { m -> Log.w(TAG, m) } }
        _session.value = probe
        var connected = false
        try {
            transport.connect()
            connected = true
            return transport
        } catch (e: LanConnectException) {
            Log.w(TAG, "LAN connect failed (${e.failure}); falling back to Bluetooth")
            if (tokenPolicy.invalidatesToken(e.failure)) {
                // The device rotated its token (ERROR 14 at the handshake). The stored copy is dead:
                // drop it so the Bluetooth session we are about to open fetches a fresh one.
                knownDeviceDao.setLanToken(address, null, null)
                tokenPolicy.markStale(address)
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(e.failure)))
            } else {
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(e.failure)))
            }
            return null
        } catch (e: TransportException) {
            Log.w(TAG, "LAN connect failed (${e.message}); falling back to Bluetooth")
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(null)))
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LAN connect failed unexpectedly (${e::class.simpleName}); falling back to Bluetooth")
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(null)))
            return null
        } finally {
            if (!connected) {
                if (_session.value === probe) _session.value = null
                transport.close()
            }
        }
    }

    /**
     * The stored LAN token as bytes, decrypted only here, at the moment a LAN transport is built.
     * Null (use Bluetooth) when none is stored, when it is malformed, or when the Keystore is
     * unavailable right now. The last case keeps the credential for the next attempt.
     */
    private suspend fun lanTokenBytes(address: String): ByteArray? {
        val hex = try {
            lanCredentials.lanToken(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stored LAN token unavailable (${e::class.simpleName}); using Bluetooth this time")
            return null
        } ?: return null
        return try {
            hex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stored LAN token is malformed; ignoring it")
            null
        }
    }

    private fun resetDerivedState() {
        identityReady.value = false
        connectionFailure.value = null
        _info.value = null
        _status.value = null
        _statusRaw.value = null
        _live.value = null
        _manifest.value = ManifestState.Idle
        _sync.value = SyncProgress()
        _archiveTimeSummary.value = ArchiveTimeSummary()
        _config.value = null
        _wifiScan.value = WifiScanState.Idle
        _logTail.value = LogTailState.Idle
    }

    /**
     * Connect [transport] (if not already), read `info`, persist identity, fetch token/config.
     * [watch] = false (background sync) skips the lost-link auto-reconnect: the worker has no user
     * to keep informed and simply tries again at its next period.
     */
    private suspend fun openSession(transport: AqTransport, address: String, name: String?, fake: Boolean, watch: Boolean = true, claim: Long? = null): Boolean {
        val session = (_session.value?.takeIf { it.transport === transport } ?: DeviceSession(transport))
            .also { it.staleFrameLogger = { m -> Log.w(TAG, m) } }
        _session.value = session
        try {
            transport.connect()
        } catch (e: TransportException) {
            Log.w(TAG, "connect failed (${e.message})")
            connectionFailure.value = e.message ?: "connection failed"
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.ConnectFailed))
            return false
        }
        startNotificationCollection(session)
        try {
            val info = session.readInfo()
            val proto = info.proto
            if (proto != null && proto > AqUuids.PROTOCOL_VERSION) {
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.NewerProtocol(proto, AqUuids.PROTOCOL_VERSION)))
            }
            val now = clock.nowEpochMs()
            val previous = knownDeviceDao.get(address)
            val authenticatedBle = transport.kind == TransportKind.BLE || fake
            val expected = previous?.let { DeviceContinuityPolicy.Identity(it.address, it.deviceId, it.station) }
            val continuity = DeviceContinuityPolicy.evaluate(expected, DeviceContinuityPolicy.Identity(address, info.dev, info.station), authenticatedBle)
            if (continuity is DeviceContinuityPolicy.Decision.Reject) {
                Log.w(TAG, "identity rejected (${continuity.reason})")
                connectionFailure.value = continuity.reason.name
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.IdentityRejected(continuity.reason)))
                return false
            }
            continuity as DeviceContinuityPolicy.Decision.Accept
            if (identityLease == null && info.dev != null) {
                identityLease = identityClaims.claim(info.dev!!, this)
                if (identityLease == null) {
                    connectionFailure.value = "hardware already connected through another endpoint"
                    _events.tryEmit(DeviceEvent.Error(DeviceMessage.HardwareInUse))
                    return false
                }
            }
            previous?.station?.let { station -> stationEpochDao.recordIfAbsent(StationEpoch(address, station, previous.deviceId, previous.lastConnectedEpochMs ?: now, false)) }
            stationEpochDao.recordIfAbsent(StationEpoch(address, info.station!!, info.dev, now, authenticatedBle))
            if (continuity.invalidateLanToken) {
                knownDeviceDao.setLanToken(address, null, null)
                tokenPolicy.markStale(address)
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.StationChanged))
            }
            knownDeviceDao.upsert(
                KnownDevice(
                    address = address,
                    name = name ?: transport.displayName ?: previous?.name,
                    station = info.station,
                    deviceId = info.dev,
                    firmware = info.fw,
                    lastSeenEpochMs = now,
                    lastConnectedEpochMs = now,
                    lanToken = previous?.lanToken?.takeUnless { continuity.invalidateLanToken },
                    lanPort = previous?.lanPort?.takeUnless { continuity.invalidateLanToken },
                    autoSync = previous?.autoSync ?: false,
                    companionAssociationId = previous?.companionAssociationId,
                )
            )
            _info.value = info
            identityReady.value = true
            suspendRunCatching { transport.readStatus().decodeToString() }.onSuccess { raw ->
                _statusRaw.value = raw
                runCatching { DeviceStatus.parse(raw) }.onSuccess { _status.value = TimedStatus(it, clock.nowEpochMs()) }
            }
            suspendRunCatching { session.readLive() }.onSuccess { _live.value = TimedLive(it, clock.nowEpochMs()) }
            info.station?.let { station -> scope.launch { if (_session.value === session) updateArchiveTimeSummary(station) } }
            recordDiagnostic("connect", "${transport.kind} ready; boot=${info.boot ?: "unknown"}; station=${info.station ?: "unknown"}")

            val v2 = (proto ?: 1) >= 2
            if (v2) {
                // Token: once, over BLE only, when we do not hold one (or the LAN handshake just
                // said ours is stale, see tryLan). Never logged.
                if (tokenPolicy.shouldFetch(transport.kind, proto, previous?.lanToken?.takeUnless { continuity.invalidateLanToken })) {
                    if (fetchTokenLocked(session, address, quiet = true) && tokenPolicy.onFetched(address)) {
                        _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanTokenRenewed))
                    }
                }
                suspendRunCatching { session.getConfig() }
                    .onSuccess { _config.value = it }
                    .onFailure { Log.w(TAG, "GET_CONFIG failed: ${it.message}") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "device verification failed (${e.message})")
            connectionFailure.value = "verification failed (${e.message})"
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.VerificationFailed))
            return false
        }
        // Verification and enrollment must finish before link loss can start an automatic
        // reconnect. A rejected or half-open session must never re-enroll itself later.
        if (watch && claim != null && connectionIntent.owns(claim)) watchLink(session, address, name, fake, claim)
        return true
    }

    private suspend fun fetchTokenLocked(session: DeviceSession, address: String, quiet: Boolean): Boolean = try {
        val t = session.getToken()
        knownDeviceDao.setLanToken(address, t.token.toHex(), t.port)
        if (!quiet) _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanTokenStored))
        true
    } catch (e: NotAllowedOnLinkException) {
        if (!quiet) _events.tryEmit(DeviceEvent.Error(DeviceMessage.TokenNeedsBluetooth))
        false
    } catch (e: DeviceErrorException) {
        Log.w(TAG, "GET_TOKEN rejected (${e.message})")
        if (!quiet) _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.GET_TOKEN)))
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "GET_TOKEN failed: ${e.message}")
        if (!quiet) _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.GET_TOKEN)))
        false
    }

    private fun startNotificationCollection(session: DeviceSession) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            launch { session.status.collect { if (_session.value === session) _status.value = TimedStatus(it, clock.nowEpochMs()) } }
            launch { session.statusJson.collect { if (_session.value === session) _statusRaw.value = it } }
            launch { session.live.collect { if (_session.value === session) _live.value = TimedLive(it, clock.nowEpochMs()) } }
        }
        synchronized(this) { notificationJob.also { notificationJob = job } }?.cancel()
        job.start()
    }

    fun disconnect() {
        val claim = connectionIntent.claim()
        cancelPendingConnect()
        cancelReconnect()
        scope.launch { connectMutex.withLock { if (connectionIntent.owns(claim)) closeSessionLocked() } }
    }

    /** [disconnect] that returns once the slot is empty (so a background job queued right after does not find the session). */
    suspend fun disconnectNow() {
        val claim = connectionIntent.claim()
        cancelPendingConnect()
        cancelReconnect()
        connectMutex.withLock { if (connectionIntent.owns(claim)) closeSessionLocked() }
    }

    /**
     * Close the current session. Runs to the end even when the caller is being cancelled: a close
     * that stopped half way would leak the transport, the identity claim or the `closing` flag.
     */
    private suspend fun closeSessionLocked() = withContext(NonCancellable) {
        val (watch, transfer, notifications) = synchronized(this@DeviceConnectionRepository) {
            closing = true
            Triple(linkWatchJob, syncJob, notificationJob).also {
                linkWatchJob = null
                syncJob = null
                notificationJob = null
            }
        }
        watch?.cancel()
        transfer?.cancelAndJoin()
        notifications?.cancel()
        // Drop the session from the slot *before* the transport reports Disconnected, so nothing
        // mistakes this deliberate close for a lost link.
        val s = _session.value
        _session.value = null
        identityReady.value = false
        if (s != null) {
            suspendRunCatching { s.transport.disconnect() }
            s.transport.close()
        }
        identityLease?.close()
        identityLease = null
        _sync.update { it.copy(running = false, current = null) }
        synchronized(this@DeviceConnectionRepository) { closing = false }
    }

    // ------------------------------------------------------------- link watch

    // Job handles below and in the sync section are read and written only under
    // `synchronized(this)`, so a cancel from the notification's Stop (another thread) always sees
    // the job that is about to run or is running, never a stale or half-published one.
    private var linkWatchJob: Job? = null
    private var reconnectJob: Job? = null

    /** Bumped by [cancelReconnect]; a reconnect decided before the bump never starts. */
    private var reconnectGeneration = 0L

    /**
     * Once [session] is Ready, wait for it to stop being Ready while it is still the current
     * session — that is a link lost on its own (LAN socket destroyed by the OS in the background,
     * GATT 133, device reboot), never a [disconnect] (which clears the slot first). Then retry
     * [RECONNECT_ATTEMPTS] times, [RECONNECT_DELAY_MS] apart, with the normal transport rule: LAN
     * if the device is still discovered, else Bluetooth — so a LAN link killed by the background
     * restriction comes back over BLE within seconds. A sync that was running — or that had just
     * died of the pipe itself ("no response from device within 60 s", socket error), see
     * [SyncResumePolicy] — is resumed; a user Stop or a completed run is not.
     */
    private fun watchLink(session: DeviceSession, address: String, name: String?, fake: Boolean, claim: Long) {
        val watch = scope.launch(start = CoroutineStart.LAZY) {
            val lost = session.connectionState.first { !it.isReady && it !is ConnectionState.Connecting && !it.isBusy }
            val generation = synchronized(this@DeviceConnectionRepository) { reconnectGeneration }
            if (_session.value !== session || !connectionIntent.owns(claim)) return@launch
            val reason = (lost as? ConnectionState.Failed)?.reason ?: "device closed the connection"
            val sync = _sync.value
            val resume = SyncResumePolicy.shouldResume(sync.running, sync.endCause, sync.finishedAtEpochMs, clock.nowEpochMs())
            Log.w(TAG, "link lost ($reason); auto-reconnect starts, resumeSync=$resume (running=${sync.running}, lastEnd=${sync.endCause})")
            val reconnect = scope.launch(start = CoroutineStart.LAZY) { reconnectLoop(address, name, fake, reason, resume, claim) }
            val me = coroutineContext[Job]
            val start = synchronized(this@DeviceConnectionRepository) {
                // A close replaced this watcher, or a Stop arrived after the loss was seen: no reconnect.
                if (linkWatchJob !== me || reconnectGeneration != generation) {
                    false
                } else {
                    reconnectJob?.cancel()
                    reconnectJob = reconnect
                    true
                }
            }
            if (!start) {
                reconnect.cancel()
                return@launch
            }
            reconnect.invokeOnCompletion { synchronized(this@DeviceConnectionRepository) { if (reconnectJob === reconnect) reconnectJob = null } }
            reconnect.start()
        }
        synchronized(this) { linkWatchJob.also { linkWatchJob = watch } }?.cancel()
        watch.start()
    }

    private suspend fun reconnectLoop(address: String, name: String?, fake: Boolean, reason: String, resumeSync: Boolean, claim: Long) {
        try {
            for (attempt in 1..RECONNECT_ATTEMPTS) {
                if (!connectionIntent.owns(claim)) return
                _reconnecting.value = ReconnectState(attempt, RECONNECT_ATTEMPTS, reason)
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.Reconnecting(attempt, RECONNECT_ATTEMPTS)))
                delay(RECONNECT_DELAY_MS)
                val ok = connectMutex.withLock {
                    if (!connectionIntent.owns(claim)) return@withLock null
                    // A newer session appeared meanwhile (user action): stop.
                    val cur = _session.value
                    if (cur != null && cur.connectionState.value.isReady) return@withLock true
                    // No LIST here when a resume follows: the resume issues its own and waits for it.
                    val opened = connectLocked(address, name, fake, TransportPreference.AUTO, claim, listAfter = !resumeSync)
                    if (!connectionIntent.owns(claim)) {
                        closeSessionLocked()
                        null
                    } else {
                        opened
                    }
                }
                if (ok == null) return
                if (ok) {
                    if (resumeSync && connectionIntent.owns(claim)) resumeAfterReconnect()
                    return
                }
            }
            if (connectionIntent.owns(claim)) _events.tryEmit(DeviceEvent.Error(DeviceMessage.ReconnectFailed(RECONNECT_ATTEMPTS)))
        } finally {
            if (connectionIntent.owns(claim)) _reconnecting.value = null
        }
    }

    /**
     * Resume a transfer on the session that was just re-established. The plan comes from this
     * session's own `LIST` ([planFor]), never from the shared [plan] flow, which can still hold the
     * previous session's plan for a moment after the reconnect.
     */
    private suspend fun resumeAfterReconnect() {
        val session = _session.value ?: return
        val plan = withTimeoutOrNull(MANIFEST_WAIT_MS) { if (refreshManifestNow(session)) planFor(session) else null }
        if (plan == null) {
            Log.w(TAG, "no plan for the re-established session; not resuming")
            return
        }
        startSync(plan.toDownload)
    }

    /**
     * The plan for [session], computed from the listing that session loaded and the archive as it
     * is now. Null when the session is no longer current or has not listed yet.
     */
    private suspend fun planFor(session: DeviceSession): SyncPlan? {
        if (_session.value !== session) return null
        val station = _info.value?.station ?: return null
        val loaded = _manifest.value.last ?: return null
        val archived = archivedFileDao.observeAll().first().map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
        val plan = SyncPlanner.plan(station, loaded.files, archived, archive.partialLengths(station))
        return plan.takeIf { _session.value === session }
    }

    // ----------------------------------------------------------------- manifest

    fun refreshManifest() {
        val session = _session.value ?: return
        scope.launch { refreshManifestNow(session) }
    }

    /**
     * One `LIST`. The previous listing survives a failure (`ManifestState.failed` keeps `last`): a
     * refresh that dies — link lost, timeout — must not blank the Files screen or null the plan.
     * There is no retry on the same session: a lost link is re-established by [watchLink], whose
     * [connectLocked] issues a fresh `LIST`; a timeout means the device is not answering and a
     * second request would only queue behind the stale reply ([DeviceSession] drains it first).
     */
    private suspend fun refreshManifestNow(session: DeviceSession): Boolean {
        if (_session.value !== session) return false
        _manifest.update { it.refreshing() }
        return try {
            val (files, end) = session.list()
            if (_session.value !== session) return false
            _manifest.update { it.loaded(files, end, clock.nowEpochMs()) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LIST failed: ${e.message}")
            if (_session.value === session) _manifest.update { it.failed(e.message ?: "LIST failed") }
            false
        }
    }

    // --------------------------------------------------------------------- sync

    /**
     * Explicit Smart Sync: inspect clock quality, apply the user's SET_TIME consent policy, then
     * download every immutable missing file. FLUSH is not part of this operation.
     */
    fun smartSync(mode: ClockSyncMode, clockConfirmed: Boolean = false) {
        if (_sync.value.running) return
        val session = interactiveSession() ?: return
        scope.launch {
            val decision = SmartSyncPolicy.decide(_status.value?.status, mode)
            recordDiagnostic("clock", "${decision.quality}: ${decision.reason}; action=${decision.action}")
            when (decision.action) {
                ClockSyncAction.ASK_USER -> if (clockConfirmed) {
                    if (!setTimeFromPhoneNow(session, quiet = false)) return@launch
                } else {
                    _events.emit(DeviceEvent.ClockConfirmationRequired(decision.quality))
                    return@launch
                }
                ClockSyncAction.SET_TIME -> {
                    if (!setTimeFromPhoneNow(session, quiet = false)) return@launch
                }
                ClockSyncAction.CONTINUE -> Unit
            }
            val latest = plan.value ?: run {
                _events.emit(DeviceEvent.Error(DeviceMessage.FileListNotReady))
                return@launch
            }
            recordDiagnostic("plan", "${latest.toDownload.size} file(s), ${latest.missingBytes} byte(s) missing")
            startSync(latest.toDownload)
        }
    }

    /** Download every file the plan marks as missing or partial, without changing device state. */
    fun syncAll() {
        val plan = this.plan.value ?: return
        startSync(plan.toDownload)
    }

    fun syncOne(name: String) {
        val plan = this.plan.value ?: return
        val entry = plan.entries.firstOrNull { it.name == name } ?: return
        if (entry.state == FileSyncState.SYNCED || entry.state == FileSyncState.INVALID_NAME) return
        startSync(listOf(entry))
    }

    fun cancelSync() {
        synchronized(this) { syncJob }?.cancel()
    }

    /**
     * Stop an automatic reconnect (the user pressed Stop on the notification). Also covers a
     * reconnect that a link watcher has decided on but not yet started.
     */
    fun cancelReconnect() {
        val job = synchronized(this) {
            reconnectGeneration++
            reconnectJob.also { reconnectJob = null }
        }
        job?.cancel()
        _reconnecting.value = null
    }

    /**
     * Pull [entries]. Returns the job (so a caller can await it) or null when nothing started.
     *
     * [foregroundService] = true (user-started syncs) puts the process under the dataSync foreground
     * service for the duration of the transfer; see [SyncForegroundService] for the measured reason.
     * The auto-sync worker passes false because WorkManager already runs it as a foreground worker.
     *
     * Over the LAN pipe — interactive or worker, both come through here — a [LanWifiLock] is held
     * from the first `OPEN` to the `finally`, so the phone's Wi-Fi is asked to stay out of power
     * save while bytes are flowing (measured: power save cut throughput 5× and then stalled TCP).
     * Never over Bluetooth.
     *
     * The post-sync `LIST` (a `FLUSH` or a rotation may have finalized a new file while we were
     * pulling) runs **inside** the run, while `running` is still true: the foreground service stops
     * 1.5 s after `running` turns false, and a phone that has been in the background for the
     * length of the transfer has no network grace period left — the LAN socket is destroyed the
     * moment the service goes, which is how the refresh used to die with "link lost while waiting
     * for a response" right after "Synced n of n". The worker ([refreshAfter] = false) skips it: it
     * closes the session straight after the run, so the listing would only cost radio time.
     */
    @Synchronized
    private fun startSync(entries: List<ManifestEntry>, foregroundService: Boolean = true, refreshAfter: Boolean = foregroundService): Job? {
        if (foregroundService && backgroundOwner.value != null) {
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.BackgroundOwnsSync))
            return null
        }
        if (foregroundService && _busy.value) {
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.BusyBeforeSync))
            return null
        }
        if (_sync.value.running || closing) return null
        val session = _session.value ?: return null
        val info = _info.value
        val station = info?.station ?: run {
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.NoStationId))
            return null
        }
        // max_read on every link; ReadWindow records why BLE is not given a smaller window.
        val maxRead = ReadWindow.forTransport(session.transport, info.maxRead)
        if (entries.isEmpty()) {
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.UpToDate))
            return null
        }
        val total = entries.sumOf { it.bytes - it.partialBytes }
        val started = clock.nowEpochMs()
        _sync.value = SyncProgress(running = true, filesTotal = entries.size, bytesTotal = total, startedAtEpochMs = started)
        val meter = RateMeter(monotonic)
        if (foregroundService) {
            // The fake device needs no protection, but in debug builds it runs under the service too
            // so the notification and its Stop action can be exercised without hardware.
            if (FOREGROUND_FOR_FAKE || !transportFactory.isFake(session.transport)) SyncForegroundService.start(context)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var bytesBefore = 0L
            var windowsBefore = 0
            val outcomes = ArrayList<FileOutcome>()
            val holdWifi = session.transport.kind == TransportKind.LAN
            var wifiLease: AutoCloseable? = null
            var permit = false
            var endCause = SyncEndCause.FAILURE
            try {
                collectionBudget.acquire()
                permit = true
                wifiLease = if (holdWifi) lanWifiLock.acquireLease() else null
                for (entry in entries) {
                    val fileBase = bytesBefore
                    val windowsBase = windowsBefore
                    var requiredBytes = entry.bytes - entry.partialBytes
                    val outcome = syncEngine.download(
                        session = session,
                        station = station,
                        sourceDevice = session.address,
                        name = entry.name,
                        expectedBytes = entry.bytes,
                        maxRead = maxRead,
                    ) { p ->
                        val done = fileBase + (p.bytesDone - p.resumedFrom)
                        windowsBefore = windowsBase + p.windowsRecovered
                        // A partial can be discarded after OPEN (metadata changed), or after final
                        // verification (its stored prefix was corrupt). Keep the total honest when
                        // the file must be fetched from an earlier offset than the plan expected.
                        val actualRequiredBytes = entry.bytes - p.resumedFrom
                        val totalDelta = actualRequiredBytes - requiredBytes
                        requiredBytes = actualRequiredBytes
                        _sync.update {
                            it.copy(
                                current = p,
                                bytesDone = done,
                                bytesTotal = it.bytesTotal + totalDelta,
                                bytesPerSecond = meter.update(done),
                                windowsRecovered = windowsBefore,
                            )
                        }
                    }
                    bytesBefore += requiredBytes
                    outcomes += outcome
                    _sync.update { it.copy(filesDone = outcomes.size, outcomes = outcomes.toList(), bytesDone = bytesBefore) }
                    if (outcome !is FileOutcome.Synced) {
                        Log.w(TAG, "${entry.name} not synced (${describe(outcome)})")
                        _events.tryEmit(DeviceEvent.Error(DeviceMessage.FileNotSynced(entry.name.substringAfterLast('/'), problemOf(outcome))))
                    }
                }
                val synced = outcomes.count { it is FileOutcome.Synced }
                endCause = SyncEndCause.COMPLETED
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.SyncFinished(synced, entries.size)))
                // Still under the foreground service and the WifiLock (see the KDoc): re-LIST now.
                // A failure here is the refresh's, not the sync's — the files are verified and stored.
                if (refreshAfter && session.connectionState.value.isReady) {
                    _sync.update { it.copy(current = null) }
                    refreshManifestNow(session)
                }
            } catch (e: CancellationException) {
                endCause = SyncEndCause.STOPPED
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.SyncStopped))
                throw e
            } catch (e: TransportException) {
                // The pipe failed under us (no response, link lost, socket error): if the link watcher
                // sees the loss shortly and gets the device back, it resumes this sync (SyncResumePolicy).
                endCause = SyncEndCause.TRANSPORT_FAILURE
                Log.e(TAG, "sync aborted by the transport", e)
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.SyncAborted))
            } catch (e: Exception) {
                Log.e(TAG, "sync aborted", e)
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.SyncAborted))
            } finally {
                wifiLease?.close()
                if (permit) collectionBudget.release()
                _sync.update { it.copy(running = false, current = null, finishedAtEpochMs = clock.nowEpochMs(), endCause = endCause) }
                // A run that ended early for a non-transport reason (a Stop, a local I/O error) still
                // has a live link: refresh so the plan reflects what did arrive. COMPLETED refreshed
                // above; TRANSPORT_FAILURE has no link to ask (watchLink re-LISTs after reconnecting).
                if (refreshAfter && endCause != SyncEndCause.COMPLETED && endCause != SyncEndCause.TRANSPORT_FAILURE && session.connectionState.value.isReady) {
                    refreshManifest()
                }
                // Bring the history tiers up to date with whatever arrived; cheap when nothing did.
                scope.launch {
                    suspendRunCatching {
                        historyDeriver.deriveStale()
                        if (_session.value === session) updateArchiveTimeSummary(station)
                    }.onFailure {
                        recordDiagnostic("derive", "failed: ${it.message ?: it::class.simpleName}")
                        Log.w(TAG, "derive after sync failed: ${it.message}")
                    }
                }
            }
        }
        syncJob = job
        job.invokeOnCompletion { synchronized(this) { if (syncJob === job) syncJob = null } }
        job.start()
        return job
    }

    // ---------------------------------------------------------- background sync

    /**
     * One unattended sync of [address] for the auto-sync worker: connect over [lanTarget] (host/port
     * from a fresh mDNS browse, token from Room) or Bluetooth straight to the bonded address
     * ([bleConnectTimeoutMs], no pairing), LIST, pull what the archive lacks, disconnect again.
     *
     * Guard: this controller's session or reconnect belongs to its enrolled device only. An owned
     * target is skipped; other device controllers can continue concurrently. Progress is visible on [sync] like a
     * user-started run so the worker can mirror it into its notification. Nothing here starts the
     * app's own foreground service; the worker is WorkManager's foreground worker.
     */
    suspend fun syncInBackground(address: String, lanTarget: LanDevice?, bleConnectTimeoutMs: Long = BACKGROUND_BLE_CONNECT_MS, owner: String = "background"): BackgroundSyncResult {
        check(boundAddress == null || boundAddress == address)
        val known = knownDeviceDao.get(address)
        val name = known?.name
        fun skip(reason: String, kind: TransportKind? = null) = BackgroundSyncResult(address, name, kind, 0, 0, 0, skipped = reason)

        val session: DeviceSession = connectMutex.withLock {
            val cur = _session.value
            if (cur != null) {
                return if (cur.address == address) skip("the app has an interactive session with this device") else skip("another session is active (${cur.address})")
            }
            if (_reconnecting.value != null) return skip("a reconnect is in progress")
            val transport: AqTransport = try {
                if (lanTarget != null) {
                    val host = lanTarget.host
                    if (known?.hasLanToken != true || host == null) return skip("no token or address for the LAN link")
                    if (known.deviceId != null && !known.deviceId.equals(lanTarget.dev, ignoreCase = true)) return skip("LAN discovery identity disagrees with the enrolled device")
                    val port = lanTarget.port.takeIf { it in 1..65535 } ?: known.lanPort ?: return skip("no LAN port")
                    val token = lanTokenBytes(address) ?: return skip("LAN token unavailable")
                    transportFactory.lan(address, name, host, port, token)
                } else {
                    transportFactory.bleUnattended(address, bleConnectTimeoutMs)
                }
            } catch (e: TransportException) {
                return skip("cannot build transport: ${e.message}", if (lanTarget != null) TransportKind.LAN else TransportKind.BLE)
            } catch (e: IllegalArgumentException) {
                return skip("malformed token or address")
            }
            if (!tryBeginBackground(owner)) {
                transport.close()
                return skip("a device command is in progress")
            }
            resetDerivedState()
            val opened = try {
                openSession(transport, address, name, fake = false, watch = false)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { closeSessionLocked(); endBackground(owner) }
                throw e
            }
            if (!opened) {
                closeSessionLocked()
                endBackground(owner)
                if (lanTarget != null) tokenPolicy.clear(address)
                return skip("connect failed", transport.kind)
            }
            _session.value ?: run {
                endBackground(owner)
                return skip("session vanished", transport.kind)
            }
        }
        try {
            val displayName = session.transport.displayName ?: name
            val kind = session.transport.kind
            if (!refreshManifestNow(session)) return skip("LIST failed", kind).copy(deviceName = displayName)
            // This session's own listing, not the shared plan flow (it can lag one session behind).
            val plan = planFor(session) ?: return skip("no plan", kind).copy(deviceName = displayName)
            val wanted = plan.toDownload.size
            val job = startSync(plan.toDownload, foregroundService = false)
                ?: return BackgroundSyncResult(address, displayName, kind, 0, 0, 0)
            job.join()
            val outcomes = _sync.value.outcomes
            val synced = outcomes.filterIsInstance<FileOutcome.Synced>().filter { !it.alreadyArchived }
            return BackgroundSyncResult(
                address = address,
                deviceName = displayName,
                transport = kind,
                newFiles = synced.size,
                newBytes = synced.sumOf { it.bytes },
                failures = outcomes.count { it !is FileOutcome.Synced },
                incomplete = outcomes.size < wanted,
            )
        } finally {
            // Runs on cancellation too (the user pressed Stop on the worker's notification): the
            // sync job is cancelled and the link closed even though this coroutine is already dead.
            withContext(NonCancellable) {
                connectMutex.withLock { if (_session.value === session) closeSessionLocked(); endBackground(owner) }
            }
        }
    }

    /** True when a session to [address] is Ready right now. */
    fun isConnectedTo(address: String): Boolean = _session.value?.let { it.address == address && it.connectionState.value.isReady } == true

    /** Address of the session the app holds right now (Ready or still connecting), or null. */
    val interactiveSessionAddress: String? get() = _session.value?.address

    /** Per-device opt-in to unattended syncs; the scheduler observes the count and (un)registers the periodic work. */
    suspend fun setAutoSync(address: String, enabled: Boolean) = withContext(io) {
        knownDeviceDao.setAutoSync(address, enabled)
    }

    /**
     * Record the Companion Device Manager association behind "wake when nearby" (null = removed).
     * The framework holds the association itself; this is the app's memory of it.
     */
    suspend fun setCompanionAssociation(address: String, associationId: Int?) = withContext(io) {
        knownDeviceDao.setCompanionAssociation(address, associationId)
    }

    private suspend fun updateArchiveTimeSummary(station: String) {
        val files = archivedFileDao.forStation(station).filter { it.indexed && (it.purpose == null || it.purpose == "telemetry") }
        val estimated = files.filter { it.hasEstimatedUtc }
        val unplaced = files.filter { !it.hasUtc && !it.hasEstimatedUtc }
        if (_info.value?.station != station) return
        _archiveTimeSummary.value = ArchiveTimeSummary(
            files = files.size,
            rows = files.sumOf { it.rows ?: 0L },
            realTimeRows = files.sumOf { it.utcRows ?: 0L },
            estimatedFiles = estimated.size,
            estimatedRows = estimated.sumOf { it.rows ?: 0L },
            unplacedFiles = unplaced.size,
            unplacedRows = unplaced.sumOf { it.rows ?: 0L },
            unplacedBoots = unplaced.mapNotNull { it.bootId }.distinct().size,
        )
        val summary = _archiveTimeSummary.value
        recordDiagnostic(
            "timeline",
            "${summary.files} file(s), ${summary.realTimeRows} UTC row(s), ${summary.estimatedRows} estimated row(s), ${summary.unplacedRows} unplaced row(s)",
        )
        if (summary.unplacedRows > 0) {
            _events.emit(DeviceEvent.Info(DeviceMessage.UnplacedReadings(summary.unplacedRows)))
        }
    }

    private fun recordDiagnostic(step: String, detail: String) {
        _syncDiagnostics.update { (it + SyncDiagnostic(clock.nowEpochMs(), step, detail)).takeLast(100) }
    }

    private fun problemOf(o: FileOutcome): DeviceMessage.FileProblem = when (o) {
        is FileOutcome.Mismatch -> DeviceMessage.FileProblem.CHECK_FAILED
        is FileOutcome.DeviceError -> DeviceMessage.FileProblem.DEVICE_REFUSED
        is FileOutcome.Synced, is FileOutcome.Failed -> DeviceMessage.FileProblem.FAILED
    }

    private fun describe(o: FileOutcome): String = when (o) {
        is FileOutcome.Synced -> "synced"
        is FileOutcome.Mismatch -> o.reason
        is FileOutcome.DeviceError -> o.message
        is FileOutcome.Failed -> o.message
    }

    /** A worker-owned session can be observed, but interactive commands cannot take it over. */
    private fun interactiveSession(): DeviceSession? {
        if (backgroundOwner.value != null) {
            _events.tryEmit(DeviceEvent.Info(DeviceMessage.BackgroundOwnsCommand))
            return null
        }
        return _session.value
    }

    // ------------------------------------------------------------ operation gate

    /**
     * Take the command slot. False when another command or a background run holds the device, or
     * (with [refuseWhileSyncing]) a transfer is running. Check and set are one atomic step.
     */
    private fun tryBeginCommand(refuseWhileSyncing: Boolean = false): Boolean = synchronized(this) {
        if (_busy.value || _backgroundOwner.value != null) return false
        if (refuseWhileSyncing && _sync.value.running) return false
        _busy.value = true
        true
    }

    private fun endCommand() {
        synchronized(this) { _busy.value = false }
    }

    private fun tryBeginBackground(owner: String): Boolean = synchronized(this) {
        if (_busy.value || _backgroundOwner.value != null) return false
        _backgroundOwner.value = owner
        true
    }

    private fun endBackground(owner: String) {
        synchronized(this) { if (_backgroundOwner.value == owner) _backgroundOwner.value = null }
    }

    private fun refuseBusy() {
        _events.tryEmit(DeviceEvent.Info(DeviceMessage.Busy))
    }

    // ----------------------------------------------------------------- controls

    /** SET_TIME with the phone's clock. Only rows sampled afterwards get UTC (contract). */
    fun setTimeFromPhone() {
        val session = interactiveSession() ?: return
        scope.launch { setTimeFromPhoneNow(session, quiet = false) }
    }

    private suspend fun setTimeFromPhoneNow(session: DeviceSession, quiet: Boolean): Boolean = try {
        val epoch = clock.nowEpochMs() / 1000
        val r = session.setTime(epoch)
        recordDiagnostic("clock", "SET_TIME accepted at epoch ${r.epochSeconds}")
        if (!quiet) _events.emit(DeviceEvent.Info(DeviceMessage.ClockSet))
        suspendRunCatching { session.readStatus() }.onSuccess { _status.value = TimedStatus(it, clock.nowEpochMs()) }
        true
    } catch (e: DeviceErrorException) {
        recordDiagnostic("clock", "SET_TIME rejected: ${e.message}")
        if (!quiet) _events.emit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.SET_TIME)))
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recordDiagnostic("clock", "SET_TIME failed: ${e.message ?: e::class.simpleName}")
        if (!quiet) _events.emit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.SET_TIME)))
        false
    }

    /** FLUSH: finalize the RAM batch into a short file so it can be pulled now. */
    fun flush() {
        val session = interactiveSession() ?: return
        scope.launch {
            try {
                val r = session.flush()
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.Finalized(r.rows, r.filesFinalized)))
                refreshManifest()
            } catch (e: DeviceErrorException) {
                Log.w(TAG, "FLUSH rejected (${e.message})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.FLUSH)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "FLUSH failed (${e.message})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.FLUSH)))
            }
        }
    }

    fun requestStatus() {
        val session = interactiveSession() ?: return
        scope.launch { suspendRunCatching { session.requestStatus() } }
    }

    // ---------------------------------------------------------------- v2 controls

    fun refreshConfig() {
        val session = interactiveSession() ?: return
        scope.launch {
            try {
                _config.value = session.getConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GET_CONFIG failed (${e.message})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.GET_CONFIG)))
            }
        }
    }

    /**
     * `SET_CONFIG`. Validation already happened in [ConfigPatch]; `ERROR` 12 names the key the
     * device still rejected. A `ble.*` change is reported as pending until the next boot.
     */
    fun applyConfig(patch: ConfigPatch, change: DeviceMessage.ConfigChange = DeviceMessage.ConfigChange.SETTINGS) {
        val session = interactiveSession() ?: return
        if (patch.isEmpty) return
        if (!tryBeginCommand()) return refuseBusy()
        scope.launch {
            try {
                val snap = session.setConfig(patch)
                _config.value = snap
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.ConfigSaved(change, snap.rebootRequired)))
            } catch (e: InvalidConfigException) {
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.ConfigKeyRejected(e.key)))
            } catch (e: DeviceErrorException) {
                Log.w(TAG, "SET_CONFIG rejected (code ${e.frame.code})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.SET_CONFIG)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SET_CONFIG failed (${e::class.simpleName})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.SET_CONFIG)))
            } finally {
                endCommand()
            }
        }
    }

    /** Await the device acknowledgement so installation history never records an unprovisioned label. */
    suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot {
        val session = interactiveSession() ?: throw IllegalStateException("Connect to the device first")
        if (!tryBeginCommand(refuseWhileSyncing = true)) throw IllegalStateException("Wait for the current device task to finish")
        try {
            return session.setConfig(patch).also { _config.value = it }
        } finally {
            endCommand()
        }
    }

    fun startWifiScan() {
        val session = interactiveSession() ?: return
        if (_wifiScan.value is WifiScanState.Scanning) return
        scope.launch {
            _wifiScan.value = WifiScanState.Scanning
            try {
                val r = session.wifiScan()
                _wifiScan.value = WifiScanState.Done(r.accessPoints.sortedByDescending { it.rssi }, clock.nowEpochMs())
            } catch (e: WifiUnavailableException) {
                _wifiScan.value = WifiScanState.Failed("WIFI_SCAN unavailable", unavailable = true)
            } catch (e: DeviceErrorException) {
                _wifiScan.value = WifiScanState.Failed(e.message ?: "WIFI_SCAN rejected", unavailable = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _wifiScan.value = WifiScanState.Failed("WIFI_SCAN failed (${e.message})", unavailable = false)
            }
        }
    }

    fun clearWifiScan() {
        if (_wifiScan.value !is WifiScanState.Scanning) _wifiScan.value = WifiScanState.Idle
    }

    /**
     * `REBOOT` → `REBOOTING delay_ms`, then drop the link and reconnect once the device is back
     * (BLE advertising resumes a few seconds after boot; the same transport rule applies again).
     *
     * This is the **only** caller of [DeviceSession.reboot] and is itself called only from the
     * Device screen's confirmation dialog (`DeviceViewModel.reboot`). Guards, in case a UI path ever
     * re-fires: refused while a transfer is running (a reboot would kill it and finalize the RAM
     * batch under it), and [_busy] is taken synchronously with compare-and-set so two calls in the
     * same instant send one `REBOOT`, not two.
     */
    fun rebootAndReconnect() {
        val session = interactiveSession() ?: return
        if (_sync.value.running) {
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.NoRebootWhileSyncing))
            return
        }
        if (!tryBeginCommand(refuseWhileSyncing = true)) return refuseBusy()
        val address = session.address
        val name = session.transport.displayName
        val fake = isFake
        val claim = connectionIntent.claim()
        scope.launch {
            try {
                if (!connectionIntent.owns(claim)) return@launch
                if (_sync.value.running) throw IllegalStateException("a sync started meanwhile; not rebooting")
                val r = session.reboot()
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.Rebooting))
                delay(r.delayMs.toLong().coerceIn(100L, 5_000L))
                connectMutex.withLock { if (connectionIntent.owns(claim)) closeSessionLocked() }
                if (!connectionIntent.owns(claim)) return@launch
                delay(REBOOT_RECONNECT_DELAY_MS)
                connectMutex.withLock {
                    if (!connectionIntent.owns(claim)) return@withLock
                    connectLocked(address, name, fake, TransportPreference.AUTO, claim)
                    if (!connectionIntent.owns(claim)) closeSessionLocked()
                }
            } catch (e: DeviceErrorException) {
                Log.w(TAG, "REBOOT rejected (${e.message})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.REBOOT)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "REBOOT failed (${e.message})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.REBOOT)))
            } finally {
                // A rejected reboot keeps the original session. The new explicit intent invalidated
                // its old link watcher, so re-arm it if that session still belongs to this action.
                if (connectionIntent.owns(claim) && _session.value === session) watchLink(session, address, name, fake, claim)
                endCommand()
            }
        }
    }

    fun refreshLogTail(maxBytes: Int = DeviceSession.LOG_RING_BYTES) {
        val session = interactiveSession() ?: return
        if (_logTail.value is LogTailState.Loading) return
        scope.launch {
            _logTail.value = LogTailState.Loading
            try {
                _logTail.value = LogTailState.Loaded(session.logTail(maxBytes), clock.nowEpochMs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _logTail.value = LogTailState.Failed(e.message ?: "LOG_TAIL failed")
            }
        }
    }

    /** Explicit "Fetch token" (BLE only by contract). */
    fun fetchToken() {
        val session = interactiveSession() ?: return
        if (session.transport.kind == TransportKind.LAN) {
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.TokenNeedsBluetooth))
            return
        }
        if (!tryBeginCommand()) return refuseBusy()
        scope.launch {
            try {
                fetchTokenLocked(session, session.address, quiet = false)
            } finally {
                endCommand()
            }
        }
    }

    /**
     * `lan.rotate_token=1`: the device forgets the old token and drops every LAN session, so the
     * stored copy is cleared and — over BLE — the new one is fetched right away.
     */
    fun rotateToken() {
        val session = interactiveSession() ?: return
        val address = session.address
        if (!tryBeginCommand()) return refuseBusy()
        scope.launch {
            try {
                _config.value = session.setConfig(ConfigPatch.build { rotateToken() })
                knownDeviceDao.setLanToken(address, null, null)
                tokenPolicy.clear(address)
                if (session.transport.kind == TransportKind.LAN) {
                    _events.tryEmit(DeviceEvent.Info(DeviceMessage.TokenRotated(DeviceMessage.TokenRotation.LAN_SESSION_DROPS)))
                } else if (fetchTokenLocked(session, address, quiet = true)) {
                    _events.tryEmit(DeviceEvent.Info(DeviceMessage.TokenRotated(DeviceMessage.TokenRotation.STORED)))
                } else {
                    _events.tryEmit(DeviceEvent.Info(DeviceMessage.TokenRotated(DeviceMessage.TokenRotation.FETCH_LATER)))
                }
            } catch (e: DeviceErrorException) {
                Log.w(TAG, "rotate_token rejected (code ${e.frame.code})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.ROTATE_TOKEN)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "rotate_token failed (${e::class.simpleName})")
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.ROTATE_TOKEN)))
            } finally {
                endCommand()
            }
        }
    }

    /** `ble.clear_bonds=1`: the device forgets every phone, including this one. */
    fun clearBonds() {
        applyConfig(ConfigPatch.build { clearBonds() }, DeviceMessage.ConfigChange.BONDS_CLEARED)
    }

    /** Whether the connected device has a stored LAN token (for the LAN card). */
    val currentKnownDevice: Flow<KnownDevice?> = _session.flatMapLatest { s ->
        if (s == null) flowOf(null) else knownDeviceDao.observeAll().map { list -> list.firstOrNull { it.address == s.address } }
    }

    // ------------------------------------------------------------- known devices

    val knownDevices: Flow<List<KnownDevice>> = knownDeviceDao.observeAll()

    suspend fun touchSeen(address: String, name: String?) = withContext(io) {
        knownDeviceDao.touch(address, name, clock.nowEpochMs())
    }

    suspend fun forget(address: String) = withContext(io) {
        check(boundAddress == null || boundAddress == address) { "controller belongs to another device" }
        val claim = connectionIntent.claim()
        cancelPendingConnect()
        cancelReconnect()
        connectMutex.withLock {
            if (!connectionIntent.owns(claim)) return@withLock
            // Forget must revoke the live session as well as the saved token. Otherwise the phone
            // can keep using a LAN pipe after its credentials disappear from Settings, and an
            // in-flight connection can re-create the known row while the delete is in flight.
            closeSessionLocked()
            tokenPolicy.clear(address)
            knownDeviceDao.get(address)?.companionAssociationId?.let { companionPresence.disassociate(address, it) }
            knownDeviceDao.delete(address)
        }
    }

    companion object {
        private const val TAG = "DeviceConnection"

        /** Boot + BLE stack start + advertising on the CoreS3; generous. */
        const val REBOOT_RECONNECT_DELAY_MS = 6_000L

        /** Auto-reconnect after a lost link: bounded, then the user is told. */
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 5_000L
        const val MANIFEST_WAIT_MS = 30_000L

        /** Unattended BLE connect budget: a logger that is not in range should cost seconds per period, not 30 s. */
        const val BACKGROUND_BLE_CONNECT_MS = 15_000L

        /** Debug builds run the fake device under the foreground service too, so the notification can be seen without hardware. */
        private val FOREGROUND_FOR_FAKE = earth.walkthru.aqsync.BuildConfig.DEBUG
    }
}
