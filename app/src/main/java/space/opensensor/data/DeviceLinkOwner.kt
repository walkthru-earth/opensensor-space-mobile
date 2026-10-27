package space.opensensor.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.archive.PrunedFiles
import space.opensensor.collection.CollectionLink
import space.opensensor.collection.LinkPreference
import space.opensensor.data.background.CompanionUnlinker
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.BleDisconnectCauses
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.DeviceErrorException
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.InvalidConfigException
import space.opensensor.data.ble.LogTail
import space.opensensor.data.ble.NotAllowedOnLinkException
import space.opensensor.data.ble.SecretNeedsBluetoothException
import space.opensensor.data.ble.SnapshotPoller
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.ble.WifiUnavailableException
import space.opensensor.data.coroutines.ApplicationScope
import space.opensensor.data.coroutines.IoDispatcher
import space.opensensor.data.coroutines.WallClock
import space.opensensor.data.coroutines.suspendRunCatching
import space.opensensor.data.fake.FakeTransport
import space.opensensor.data.history.BoardDescriptors
import space.opensensor.data.history.DescriptorOutcome
import space.opensensor.data.history.HistoryRefresher
import space.opensensor.data.lan.LanConnectException
import space.opensensor.data.lan.LanDevice
import space.opensensor.data.lan.LanFailure
import space.opensensor.data.lan.LanTargets
import space.opensensor.data.lan.LanTokenPolicy
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.local.database.ThingEpoch
import space.opensensor.data.local.database.ThingEpochDao
import space.opensensor.data.local.security.LanCredentialStore
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.DeviceUuids
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.hexToBytes
import space.opensensor.data.protocol.toHex
import space.opensensor.data.sync.FileArchive
import space.opensensor.data.sync.ReadWindow
import space.opensensor.data.sync.SyncListing
import space.opensensor.data.sync.SyncPlan
import space.opensensor.data.sync.ThingPlaces
import space.opensensor.data.sync.recordFrom
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkLog
import space.opensensor.link.LinkState
import space.opensensor.link.TimeoutPhase
import space.opensensor.link.describe
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * [ArchiveTimeSummary] over one thing's archive rows. [ArchiveTimeSummary.files] counts every
 * row, as the Things list does. The time figures cover indexed telemetry files only.
 */
internal fun summarizeArchive(rows: List<ArchivedFile>): ArchiveTimeSummary {
    val timed = rows.filter { it.isIndexed && (it.purpose == null || it.purpose == "telemetry") }
    val estimated = timed.filter { it.hasEstimatedUtc }
    val unplaced = timed.filter { !it.hasUtc && !it.hasEstimatedUtc }
    return ArchiveTimeSummary(
        files = rows.size,
        rows = timed.sumOf { it.rows ?: 0L },
        realTimeRows = timed.sumOf { it.utcRows ?: 0L },
        estimatedFiles = estimated.size,
        estimatedRows = estimated.sumOf { it.rows ?: 0L },
        unplacedFiles = unplaced.size,
        unplacedRows = unplaced.sumOf { it.rows ?: 0L },
        unplacedBoots = unplaced.mapNotNull { it.bootId }.distinct().size,
    )
}

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
enum class TransportPreference { AUTO, BLE_ONLY, LAN_ONLY }

/** A worker's discovered LAN endpoint or a direct, bonded BLE attempt. Never permits pairing. */
data class BackgroundLinkRequest(
    val lanTarget: LanDevice?,
    val bleConnectTimeoutMs: Long = DeviceLinkOwner.BACKGROUND_BLE_CONNECT_MS,
    val owner: String,
)

/** A link that was Ready dropped on its own; the repository is trying to get it back. */
data class ReconnectState(val attempt: Int, val maxAttempts: Int, val reason: String)

/**
 * Owns one device connection and everything derived from it. Created only by DeviceConnectionManager.
 *
 * Process-scoped (manager-owned with its own [CoroutineScope]) so a download keeps running
 * through screen rotation and navigation; ViewModels only observe and forward user intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkOwner @Inject constructor(
    private val transportFactory: TransportSource,
    private val knownDeviceDao: KnownDeviceDao,
    private val lanCredentials: LanCredentialStore,
    private val archivedFileDao: ArchivedFileDao,
    private val archive: FileArchive,
    private val lanDiscoveryState: LanTargets,
    private val historyDeriver: HistoryRefresher,
    private val companionPresence: CompanionUnlinker,
    private val identityClaims: DeviceIdentityClaims,
    private val thingEpochDao: ThingEpochDao,
    @ApplicationScope applicationScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
    /** Files removed in Settings, which the plan shows as removed instead of to sync. */
    private val pruned: PrunedFiles,
    /** Board templates the device serves over `GET_DESCRIPTOR` (#191). */
    private val boardDescriptors: BoardDescriptors,
    /** Each fixed Thing's saved cell, for its pin on Discover (#226). Only the cell, never coordinates. */
    private val places: ThingPlaces,
) {
    /**
     * Child of the process scope: one failed child never cancels its siblings, and cancelling the
     * application scope (tests) ends every job this controller started.
     */
    private val scope = CoroutineScope(applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]) + io)
    private val stateLock = ReentrantLock()
    private val tokenPolicy = LanTokenPolicy()

    init {
        // Cached served templates register before the first connect asks for one.
        scope.launch { suspendRunCatching { boardDescriptors.load() }.onFailure { Log.w(TAG, "board template cache unreadable (${it::class.simpleName})") } }
    }

    /** The ciphertext each LAN link authenticated with, so a dead token is cleared only if still stored. */
    private val lanCiphertexts: MutableMap<AndroidLink, String> = Collections.synchronizedMap(WeakHashMap())
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
     * [_backgroundOwner] while a worker owns the device. Both change only under `stateLock.withLock`
     * through [tryBeginCommand] / [reserveCollection], so a check and its set are one step and two
     * overlapping commands can never both run or clear each other's flag.
     */
    private val _busy = MutableStateFlow(false)
    private val _backgroundOwner = MutableStateFlow<String?>(null)
    private val _collectionActive = MutableStateFlow(false)
    val collectionActive: StateFlow<Boolean> = _collectionActive.asStateFlow()
    val backgroundOwner: StateFlow<String?> = _backgroundOwner.asStateFlow()
    val busy: StateFlow<Boolean> = combine(_busy, collectionActive) { command, collecting -> command || collecting }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Non-null while a lost link is being re-established automatically (see [watchLink]). */
    private val _reconnecting = MutableStateFlow<ReconnectState?>(null)
    val reconnecting: StateFlow<ReconnectState?> = _reconnecting.asStateFlow()

    // ------------------------------------------------------------ LAN discovery

    val lanDevices get() = lanDiscoveryState.devices
    val lanDiscoveryError get() = lanDiscoveryState.error

    private var boundAddress: String? = null
    private val bound = MutableStateFlow<String?>(null)
    internal val address: String get() = checkNotNull(boundAddress) { "device endpoint is not bound" }
    internal fun bind(address: String) {
        check(boundAddress == null || boundAddress == address)
        boundAddress = address
        bound.value = address
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
    private val manifestMutex = Mutex()

    /** LIVE and STATUS reads when the MTU cannot notify a full snapshot (#159). Paused by a transfer. */
    private val snapshotPoller = SnapshotPoller()

    /**
     * The last collection's progress. Kept through a disconnect and a reconnect so the last
     * ending stays visible; only the next run replaces it. One owner serves one device.
     */
    private val _sync = MutableStateFlow(SyncProgress())
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()

    /**
     * The thing this owner's device archives into: the connected `info`, else the enrolled row,
     * so the archive summary survives a disconnect.
     */
    private val archiveThing: Flow<String?> = combine(_info, bound, knownDeviceDao.observeAll()) { info, address, known ->
        info?.thing ?: known.firstOrNull { it.address == address }?.thing
    }.distinctUntilChanged()

    /**
     * What is archived for this device, straight from Room. Counts the same rows as the
     * Things list, never resets on disconnect, and follows every commit and index pass.
     */
    val archiveTimeSummary: StateFlow<ArchiveTimeSummary> = archiveThing.flatMapLatest { thing ->
        if (thing == null) flowOf(ArchiveTimeSummary()) else archivedFileDao.observeForThing(thing).map(::summarizeArchive)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, ArchiveTimeSummary())

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

    /** Manifest joined with the archive, see [planWithProgress]. */
    val plan: StateFlow<SyncPlan?> = planWithProgress(_manifest, _info, archivedFileDao::observeIdentitiesForThing, _sync, archive::partialLengths, pruned.observeAll())
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var notificationJob: Job? = null
    private var connectJob: Job? = null
    private var collectionHandoff: List<Job> = emptyList()
    private var lending = false

    @Volatile private var verifiedSessionInfo: Pair<DeviceSession, DeviceInfo>? = null
    private val connectMutex = Mutex()
    private val connectionIntent = ConnectionIntent()
    private val uiConnectionWanted = AtomicBoolean(false)

    @Volatile private var uiClaim = 0L

    @Volatile private var uiPreference = TransportPreference.AUTO

    /** Links lent to collections and not yet returned. Changed only under [connectMutex] when it grows. */
    private val lends = AtomicInteger(0)

    /** The session [lendLink] opened itself. Closed when its last lend returns, unless the UI took it. */
    private var lendOwned: DeviceSession? = null

    /** Why the last [openSession] failed, for [lendLink]. Written under [connectMutex]. */
    private var lastOpenFailure: DisconnectCause? = null

    // ------------------------------------------------------------------ connect

    /**
     * Connect to [address] (or the fake device when [fake]). Safe to call while connected to the same peer.
     *
     * Transport choice (contract, "LAN transport"): prefer LAN when the device's thing is currently
     * discovered on the network *and* a token is stored, else BLE; if the LAN connect fails for any
     * reason fall back to BLE and say so. [preference] = [TransportPreference.BLE_ONLY] skips LAN
     * (needed for `GET_TOKEN`, which is BLE-only by contract). [TransportPreference.LAN_ONLY]
     * never falls back to Bluetooth when that permission is unavailable.
     */
    fun connect(address: String, name: String?, fake: Boolean = false, preference: TransportPreference = TransportPreference.AUTO) {
        check(boundAddress == null || boundAddress == address) { "controller belongs to another device" }
        var previous: Job? = null
        val job = stateLock.withLock {
            if (_collectionActive.value) return refuseBusy()
            val claim = connectionIntent.claim()
            uiConnectionWanted.set(true)
            uiClaim = claim
            uiPreference = preference
            scope.launch(start = CoroutineStart.LAZY) {
                connectMutex.withLock {
                    if (!connectionIntent.owns(claim) || collectionActive.value) return@withLock
                    cancelReconnect()
                    connectLocked(address, name, fake, preference, claim)
                    if (!connectionIntent.owns(claim)) closeSessionLocked()
                }
            }.also {
                previous = connectJob
                connectJob = it
            }
        }
        previous?.cancel()
        job.invokeOnCompletion { stateLock.withLock { if (connectJob === job) connectJob = null } }
        job.start()
    }

    private fun cancelPendingConnect() {
        stateLock.withLock {
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
        if (existing != null && existing.address == address && existing.isUsable) {
            // A repeated Connect is a new explicit intent even when it can reuse the pipe. Give
            // the existing link watcher the new claim so future loss still reconnects it. A link a
            // collection opened now belongs to the UI too and stays open after the collection.
            lendOwned = null
            watchLink(existing, address, name, fake, claim, preference)
            return true
        }
        closeSessionLocked()
        resetDerivedState()

        knownDeviceDao.insertIfAbsent(
            KnownDevice(address, name, null, null, null, clock.nowEpochMs(), null)
        )
        val known = knownDeviceDao.get(address)

        var transport: AndroidLink? = null
        if (fake) {
            transport = transportFactory.fake(address)
        } else if (preference != TransportPreference.BLE_ONLY) {
            when (val lan = tryLan(address, name ?: known?.name, known, fallbackToBle = preference == TransportPreference.AUTO)) {
                is LanAttempt.Opened -> transport = lan.link
                LanAttempt.Refused -> return false
                LanAttempt.Skipped -> Unit
            }
        }
        if (transport == null) {
            if (preference == TransportPreference.LAN_ONLY) {
                connectionFailure.value = "Wi-Fi connection failed"
                return false
            }
            transport = try {
                transportFactory.ble(address)
            } catch (e: TransportUnavailableException) {
                reportOpenFailure(LinkClosedException(DisconnectCause.Unavailable(e.kind), e))
                return false
            }
        }
        val opened = try {
            openSession(transport, address, name, fake, claim = claim, preference = preference)
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

    private sealed interface LanAttempt {
        /** The probe session's link is open, [openSession] continues on it. */
        data class Opened(val link: AndroidLink) : LanAttempt

        /** No LAN this time. The caller may fall back to Bluetooth. */
        data object Skipped : LanAttempt

        /** The device answered and refused this app (old firmware). Bluetooth would refuse too. */
        data object Refused : LanAttempt
    }

    /**
     * Attempt a LAN session. The caller may fall back to BLE after [LanAttempt.Skipped] if its
     * preference allows it. The probe session is removed and its link closed on every failure
     * path, including an unexpected exception and cancellation.
     */
    private suspend fun tryLan(address: String, name: String?, known: KnownDevice?, fallbackToBle: Boolean = true, target: LanDevice? = null): LanAttempt {
        val thing = known?.thing ?: return LanAttempt.Skipped
        val seen = target ?: lanDevices.value[thing] ?: return LanAttempt.Skipped
        if (seen.thing != thing) return LanAttempt.Skipped
        val host = seen.host ?: return LanAttempt.Skipped
        val port = TransportSelector.lanPort(known.hasLanToken, known.deviceId, seen.dev, seen.port, known.lanPort) ?: return LanAttempt.Skipped
        val key = lanKey(address) ?: return LanAttempt.Skipped
        val transport = try {
            transportFactory.lan(address, name, host, port, key.token)
        } finally {
            key.token.fill(0)
        }
        lanCiphertexts[transport] = key.stored
        // Put the LAN attempt in the session slot so the UI shows "Authenticating on the LAN…".
        val probe = newSession(transport)
        _session.value = probe
        var connected = false
        try {
            probe.link.open()
            connected = true
            return LanAttempt.Opened(transport)
        } catch (e: LinkClosedException) {
            val cause = e.disconnectCause
            if (cause is DisconnectCause.UpdateFirmware) {
                reportOpenFailure(e)
                return LanAttempt.Refused
            }
            val failure = (e.cause as? LanConnectException)?.failure
                ?: if (cause is DisconnectCause.Auth) LanFailure.AUTH_FAILED else null
            Log.w(TAG, "LAN connect failed (${cause.describe()})")
            // A LAN-only lend reports this instead of Unreachable, so a refused or rotated token is not retried.
            val typed = if (cause !is DisconnectCause.Auth && failure == LanFailure.AUTH_FAILED) DisconnectCause.Auth(AuthKind.TOKEN_REJECTED) else cause
            lastOpenFailure = typed
            // ERROR 14 or BYE 3 (refused) and BYE 10 (rotated) at the handshake: the Bluetooth
            // session we are about to open fetches a fresh token.
            forgetInvalidatedToken(address, typed, key.stored)
            if (fallbackToBle) _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(failure)))
            return LanAttempt.Skipped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LAN connect failed unexpectedly (${e::class.simpleName})")
            if (fallbackToBle) _events.tryEmit(DeviceEvent.Info(DeviceMessage.LanFallback(null)))
            return LanAttempt.Skipped
        } finally {
            if (!connected) {
                if (_session.value === probe) _session.value = null
                // The probe's link never opened, closing it ends its jobs.
                probe.link.close("LAN probe failed")
                transport.close()
            }
        }
    }

    private fun reportOpenFailure(e: LinkClosedException) {
        lastOpenFailure = e.disconnectCause
        when (val cause = e.disconnectCause) {
            is DisconnectCause.UpdateFirmware -> {
                Log.w(TAG, "firmware update required (proto ${cause.reported})")
                connectionFailure.value = "firmware update required"
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.FirmwareUpdateRequired(cause.reported, cause.required)))
            }
            is DisconnectCause.FramingViolation -> {
                // Connected, but the info reply was missing or malformed.
                Log.w(TAG, "device verification failed (${cause.describe()})")
                connectionFailure.value = "verification failed"
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.VerificationFailed))
            }
            is DisconnectCause.Unavailable -> {
                // Bluetooth is off, missing or not permitted. Nothing was attempted.
                Log.w(TAG, "connect not started (${cause.describe()})")
                connectionFailure.value = e.message ?: "Bluetooth unavailable"
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.BluetoothUnavailable(cause.kind)))
            }
            is DisconnectCause.Auth if BleDisconnectCauses.needsPairingRepair(cause) -> {
                // The bond keys failed. Reconnecting cannot help until the user pairs again.
                Log.w(TAG, "connect failed (${cause.describe()})")
                connectionFailure.value = BleConnectionFailure.PAIRING_REPAIR_MESSAGE
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.PairingRepair))
            }
            else -> {
                Log.w(TAG, "connect failed (${cause.describe()})")
                connectionFailure.value = e.message ?: "connection failed"
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.ConnectFailed))
            }
        }
    }

    /** Ready on the transport and on the link. A link that closed itself is not reused. */
    private val DeviceSession.isUsable: Boolean
        get() = connectionState.value.isReady && link.state.value is LinkState.Ready

    /** A session whose link is opened by [openSession]. Link events carry no frame bodies. */
    private fun newSession(link: AndroidLink): DeviceSession {
        val session = link.openableSession(scope, log = LinkLog { Log.d(TAG, it.describe()) })
        val lanStored = lanCiphertexts[link]
        if (link.kind == TransportKind.LAN && lanStored != null) {
            // A LAN session the device ends with BYE 10 (token rotated) or 3 (refused), at any time.
            scope.launch {
                val closed = session.link.state.first { it is LinkState.Closed } as LinkState.Closed
                forgetInvalidatedToken(link.address, closed.cause, lanStored)
            }
        }
        return session
    }

    /**
     * Clear the LAN token this link authenticated with when [cause] proves it dead (refused or
     * rotated), so the next bonded Bluetooth session fetches a new one. Clearing, not only marking
     * stale, because a stored token is never fetched again. One conditional update clears only
     * [stored], so a token renewed meanwhile survives.
     */
    private suspend fun forgetInvalidatedToken(address: String, cause: DisconnectCause, stored: String) {
        if (!tokenPolicy.invalidatesToken(cause)) return
        if (knownDeviceDao.clearLanTokenIfStored(address, stored) == 0) return
        Log.i(TAG, "stored LAN token dropped (${cause.describe()})")
        tokenPolicy.markStale(address)
    }

    /** A decrypted LAN token and the ciphertext it came from. */
    private class LanKey(val token: ByteArray, val stored: String)

    /**
     * The stored LAN token as bytes with its ciphertext, decrypted only here, at the moment a LAN transport is built.
     * Null (use Bluetooth) when none is stored, when it is malformed, or when the Keystore is
     * unavailable right now. The last case keeps the credential for the next attempt.
     */
    private suspend fun lanKey(address: String): LanKey? {
        val credential = try {
            lanCredentials.lanCredential(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stored LAN token unavailable (${e::class.simpleName}); using Bluetooth this time")
            return null
        } ?: return null
        return try {
            LanKey(credential.tokenHex.hexToBytes(), credential.stored)
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
        _config.value = null
        _wifiScan.value = WifiScanState.Idle
        _logTail.value = LogTailState.Idle
    }

    /**
     * Connect [transport] (if not already), read `info`, persist identity, fetch token/config.
     * [watch] = false (background sync) skips the lost-link auto-reconnect: the worker has no user
     * to keep informed and simply tries again at its next period.
     */
    private suspend fun openSession(transport: AndroidLink, address: String, name: String?, fake: Boolean, watch: Boolean = true, claim: Long? = null, preference: TransportPreference = TransportPreference.AUTO): Boolean {
        // The LAN probe arrives already open on this link. A link opens once.
        val session = _session.value?.takeIf { it.transport === transport && it.link.state.value !is LinkState.Closed }
            ?: newSession(transport)
        _session.value = session
        val info = try {
            ((session.link.state.value as? LinkState.Ready)?.info ?: session.link.open()).info
        } catch (e: LinkClosedException) {
            reportOpenFailure(e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "device verification failed (${e.message})")
            lastOpenFailure = DisconnectCause.FramingViolation("info unreadable")
            connectionFailure.value = "verification failed (${e.message})"
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.VerificationFailed))
            return false
        }
        // Identity, epochs and archive paths all key on the thing, so info without one is unusable.
        val thing = info.thing ?: run {
            Log.w(TAG, "device verification failed (info has no thing)")
            lastOpenFailure = DisconnectCause.FramingViolation("info without thing")
            connectionFailure.value = "verification failed (info has no thing)"
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.VerificationFailed))
            return false
        }
        val setupDone = startNotificationCollection(session)
        try {
            val proto = info.proto
            if (proto != null && proto > DeviceUuids.PROTOCOL_VERSION) {
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.NewerProtocol(proto, DeviceUuids.PROTOCOL_VERSION)))
            }
            val now = clock.nowEpochMs()
            val previous = knownDeviceDao.get(address)
            val authenticatedBle = transport.kind == TransportKind.BLE || fake
            val expected = previous?.let { DeviceContinuityPolicy.Identity(it.address, it.deviceId, it.thing) }
            val continuity = DeviceContinuityPolicy.evaluate(expected, DeviceContinuityPolicy.Identity(address, info.dev, info.thing), authenticatedBle)
            if (continuity is DeviceContinuityPolicy.Decision.Reject) {
                Log.w(TAG, "identity rejected (${continuity.reason})")
                lastOpenFailure = DisconnectCause.Rejected("identity ${continuity.reason.name}")
                connectionFailure.value = continuity.reason.name
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.IdentityRejected(continuity.reason)))
                return false
            }
            continuity as DeviceContinuityPolicy.Decision.Accept
            if (identityLease == null && info.dev != null) {
                identityLease = identityClaims.claim(info.dev!!, this)
                if (identityLease == null) {
                    lastOpenFailure = DisconnectCause.Rejected("hardware in use")
                    connectionFailure.value = "hardware already connected through another endpoint"
                    _events.tryEmit(DeviceEvent.Error(DeviceMessage.HardwareInUse))
                    return false
                }
            }
            previous?.thing?.let { thing -> thingEpochDao.recordIfAbsent(ThingEpoch(address, thing, previous.deviceId, previous.lastConnectedEpochMs ?: now, false)) }
            thingEpochDao.recordIfAbsent(ThingEpoch(address, thing, info.dev, now, authenticatedBle))
            if (continuity.invalidateLanToken) {
                knownDeviceDao.setLanToken(address, null, null)
                tokenPolicy.markStale(address)
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.ThingChanged))
            }
            // Enrollment writes identity fields only. Auto-sync, the companion association and the
            // credential keep whatever the row holds now, never the snapshot read above (#106).
            val displayName = name ?: transport.displayName
            val enrolled = KnownDevice(address, displayName, info.thing, info.dev, info.fw, now, now)
            if (knownDeviceDao.insertIfAbsent(enrolled) == -1L) {
                knownDeviceDao.recordConnection(address, displayName, info.thing, info.dev, info.fw, now)
            }
            verifiedSessionInfo = session to info
            _info.value = info
            if (!collectionActive.value) setTimeFromPhoneNow(session, quiet = true, automatic = true)
            if (_session.value !== session) {
                lastOpenFailure = DisconnectCause.Rejected("session replaced")
                return false
            }
            identityReady.value = true
            suspendRunCatching { transport.readStatus() }.onSuccess { applyStatusRead(session, it) }
            suspendRunCatching { transport.readLive() }.onSuccess { applyLiveRead(session, it) }
            info.thing?.let { thing -> scope.launch { if (_session.value === session) reportArchiveTimeline(thing) } }
            recordDiagnostic("connect", "${transport.kind} ready")

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
            // The link is open and, on BLE, bonded. Only a desc no known template matches is fetched.
            suspendRunCatching { boardDescriptors.ensure(info, session) }
                .onSuccess { if (it !is DescriptorOutcome.Known && it !is DescriptorOutcome.Unsupported) Log.i(TAG, "board template ${it.label()}") }
                .onFailure { Log.w(TAG, "GET_DESCRIPTOR failed (${it::class.simpleName})") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "device verification failed (${e.message})")
            // A link lost during setup keeps its own, possibly retryable, cause.
            lastOpenFailure = (e as? LinkClosedException)?.disconnectCause ?: DisconnectCause.Rejected("setup failed")
            connectionFailure.value = "verification failed (${e.message})"
            _events.tryEmit(DeviceEvent.Error(DeviceMessage.VerificationFailed))
            return false
        }
        // Paced snapshot reads start once setup's own reads and requests are done.
        setupDone.complete(Unit)
        // Verification and enrollment must finish before link loss can start an automatic
        // reconnect. A rejected or half-open session must never re-enroll itself later.
        if (watch && claim != null && connectionIntent.owns(claim)) watchLink(session, address, name, fake, claim, preference)
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
        Log.w(TAG, "GET_TOKEN rejected (code ${e.frame.code})")
        if (!quiet) _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandRejected(DeviceMessage.Command.GET_TOKEN)))
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "GET_TOKEN failed (${e::class.simpleName})")
        if (!quiet) _events.tryEmit(DeviceEvent.Error(DeviceMessage.CommandFailed(DeviceMessage.Command.GET_TOKEN)))
        false
    }

    /**
     * Collect [session]'s notifications. On a link whose MTU cannot notify a full snapshot, also
     * read LIVE and STATUS at the snapshot pace once the returned signal completes (#159).
     */
    private fun startNotificationCollection(session: DeviceSession): CompletableDeferred<Unit> {
        val setupDone = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            launch { session.status.collect { if (_session.value === session) _status.value = TimedStatus(it, clock.nowEpochMs()) } }
            launch { session.statusJson.collect { if (_session.value === session) _statusRaw.value = it } }
            launch { session.live.collect { if (_session.value === session) _live.value = TimedLive(it, clock.nowEpochMs()) } }
            if (SnapshotPoller.needed(session.transport.kind, session.transport.mtu)) {
                launch {
                    setupDone.await()
                    snapshotPoller.run(
                        session.transport,
                        paused = ::transferActive,
                        onLive = { applyLiveRead(session, it) },
                        onStatus = { applyStatusRead(session, it) },
                    )
                }
            }
        }
        stateLock.withLock { notificationJob.also { notificationJob = job } }?.cancel()
        job.start()
        return setupDone
    }

    /** A collection or a lent link owns the device, so snapshot reads wait. */
    private fun transferActive(): Boolean = stateLock.withLock { _collectionActive.value || lending || lends.get() != 0 }

    private fun applyStatusRead(session: DeviceSession, raw: ByteArray) {
        if (_session.value !== session) return
        val text = raw.decodeToString()
        _statusRaw.value = text
        runCatching { DeviceStatus.parse(text) }.onSuccess { _status.value = TimedStatus(it, clock.nowEpochMs()) }
    }

    private fun applyLiveRead(session: DeviceSession, raw: ByteArray) {
        if (_session.value !== session) return
        runCatching { LiveSample.parse(raw.decodeToString()) }.onSuccess { _live.value = TimedLive(it, clock.nowEpochMs()) }
    }

    fun disconnect() {
        val claim = disconnectClaim(allowCollection = false) ?: return refuseBusy()
        scope.launch { connectMutex.withLock { if (connectionIntent.owns(claim)) closeSessionLocked() } }
    }

    /** [disconnect] that returns once the slot is empty. */
    suspend fun disconnectNow() {
        val claim = disconnectClaim(allowCollection = false) ?: return refuseBusy()
        disconnectNow(claim)
    }

    /** Capture the explicit disconnect before a facade waits for collection cleanup. */
    internal fun claimDisconnectIntent(): Long = checkNotNull(disconnectClaim(allowCollection = true))

    private fun disconnectClaim(allowCollection: Boolean): Long? {
        val (claim, jobs) = stateLock.withLock {
            if (_collectionActive.value && !allowCollection) return null
            uiConnectionWanted.set(false)
            val claim = connectionIntent.claim()
            reconnectGeneration++
            val jobs = listOfNotNull(connectJob, reconnectJob, linkWatchJob)
            connectJob = null
            reconnectJob = null
            linkWatchJob = null
            _reconnecting.value = null
            claim to jobs
        }
        jobs.forEach { it.cancel() }
        return claim
    }

    /** A newer explicit Connect wins over an older disconnect delayed by collection cleanup. */
    internal suspend fun disconnectNow(expectedClaim: Long) {
        connectMutex.withLock {
            if (connectionIntent.owns(expectedClaim) && !collectionActive.value) closeSessionLocked()
        }
    }

    /**
     * Close the current session. Runs to the end even when the caller is being cancelled: a close
     * that stopped half way would leak the transport, the identity claim or the `closing` flag.
     */
    private suspend fun closeSessionLocked() = withContext(NonCancellable) {
        val (watch, notifications) = stateLock.withLock {
            closing = true
            Pair(linkWatchJob, notificationJob).also {
                linkWatchJob = null
                notificationJob = null
            }
        }
        watch?.cancel()
        notifications?.cancel()
        // Drop the session from the slot *before* the transport reports Disconnected, so nothing
        // mistakes this deliberate close for a lost link.
        val s = _session.value
        _session.value = null
        verifiedSessionInfo = null
        lendOwned = null
        identityReady.value = false
        if (s != null) {
            s.link.close("disconnect")
            suspendRunCatching { s.transport.disconnect() }
            s.transport.close()
        }
        identityLease?.close()
        identityLease = null
        stateLock.withLock { closing = false }
    }

    // ------------------------------------------------------------- collection links

    /**
     * Lend a ready link to [address] to a `CollectionController`. Reuses the UI's session when it
     * is Ready, otherwise opens one per [prefer] without the auto reconnect, since the controller
     * retries itself. Throws [LinkClosedException] with a typed cause when no link can be had.
     *
     * Every returned link must be released once, [CollectionLink.release] is idempotent. A session
     * this call opened closes when its last lend returns.
     *
     * Bounded and cancellable. The controller joins the previous run before connecting, so a
     * connect that hung would park every later Stop then Sync. The whole call, waiting for the
     * slot included, ends after [LEND_TIMEOUT] with `Timeout(OPEN)`, and a cancel ends it at the
     * next suspension. A lend taken by a call that is cancelled or timed out is returned here.
     */
    suspend fun lendLink(address: String, prefer: LinkPreference, background: BackgroundLinkRequest? = null, forCollection: Boolean = false): CollectionLink {
        check(boundAddress == null || boundAddress == address) { "controller belongs to another device" }
        stateLock.withLock {
            if (lending || lends.get() != 0 || (_collectionActive.value && !forCollection)) throw LinkClosedException(DisconnectCause.Busy)
            lending = true
        }
        var lent: CollectionLink? = null
        try {
            withTimeoutOrNull(LEND_TIMEOUT) {
                if (collectionActive.value && !forCollection) throw LinkClosedException(DisconnectCause.Busy)
                val handoff = stateLock.withLock { collectionHandoff }
                // Idle cancellation may close a half-open link under connectMutex. Join outside it.
                handoff.forEach { it.join() }
                stateLock.withLock { if (collectionHandoff === handoff) collectionHandoff = emptyList() }
                // `lending` is set, so no new snapshot read starts. Let one in flight finish first.
                snapshotPoller.quiesce()
                connectMutex.withLock { lent = lendLocked(address, prefer, background, forCollection) }
            }
            currentCoroutineContext().ensureActive()
            val link = lent ?: throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))
            lent = null
            return link
        } finally {
            // Set only when the lend did not reach the caller: cancelled, or the timeout raced it.
            try { lent?.release() } finally { stateLock.withLock { lending = false } }
        }
    }

    private suspend fun lendLocked(address: String, prefer: LinkPreference, background: BackgroundLinkRequest?, forCollection: Boolean): CollectionLink {
        if (collectionActive.value && !forCollection) throw LinkClosedException(DisconnectCause.Busy)
        if (background != null && prefer != if (background.lanTarget == null) LinkPreference.BLE else LinkPreference.LAN) {
            throw LinkClosedException(DisconnectCause.Rejected("background link policy mismatch"))
        }
        if (backgroundOwner.value != background?.owner || _reconnecting.value != null || lends.get() != 0) {
            throw LinkClosedException(DisconnectCause.Busy)
        }
        var current = _session.value
        // A credential refusal is terminal even when this run's returned link is no longer usable.
        current?.takeIf { it.address == address }?.closedByAuth()?.let { throw LinkClosedException(it) }
        // A link a lend opened that dropped after its lends came back is ours to clear, not Busy.
        if (current != null && lends.get() == 0 && !current.isUsable && (current === lendOwned || collectionActive.value)) {
            closeSessionLocked()
            current = null
        }
        if (background != null && current != null) throw LinkClosedException(DisconnectCause.Busy)
        val session = when {
            current == null -> openForLend(address, prefer, background)
            current.address == address && current.isUsable -> current
            else -> throw LinkClosedException(DisconnectCause.Busy)
        }
        val maxRead: Int
        val thing: String
        try {
            // openSession swallows reads after verification, so the link may already be gone.
            val info = (session.link.state.value as? LinkState.Ready)?.info?.info
                ?: throw LinkClosedException((session.link.state.value as? LinkState.Closed)?.cause ?: DisconnectCause.Unreachable)
            thing = info.thing ?: throw LinkClosedException(DisconnectCause.FramingViolation("info without thing"))
            maxRead = ReadWindow.forTransport(session.transport, info.maxRead)
        } catch (e: Throwable) {
            // Never counted, so no release will close it. Close a link this call opened now.
            if (current == null) closeSessionLocked()
            throw e
        }
        // Nothing below suspends, so a lend counted here always reaches the caller's finally.
        lends.incrementAndGet()
        val returned = AtomicBoolean(false)
        return CollectionLink(session, thing, maxRead) {
            if (returned.compareAndSet(false, true)) returnLend(session)
        }
    }

    private fun DeviceSession.closedByAuth(): DisconnectCause.Auth? = (link.state.value as? LinkState.Closed)?.cause as? DisconnectCause.Auth

    /** Opens a session for a collection. Caller holds [connectMutex] and the slot is empty. */
    private suspend fun openForLend(address: String, prefer: LinkPreference, background: BackgroundLinkRequest?): DeviceSession {
        resetDerivedState()
        lastOpenFailure = null
        val known = knownDeviceDao.get(address)
        val name = known?.name
        if (prefer == LinkPreference.LAN && tokenPolicy.isStale(address) && known?.hasLanToken != true) {
            throw LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED))
        }
        val fake = address == FakeTransport.FAKE_ADDRESS || address == FakeTransport.FAKE_LAN_ADDRESS
        var link: AndroidLink? = if (fake) transportFactory.fake(address) else null
        if (link == null && prefer != LinkPreference.BLE) {
            when (val lan = tryLan(address, name, known, fallbackToBle = prefer == LinkPreference.ANY, target = background?.lanTarget)) {
                is LanAttempt.Opened -> link = lan.link
                LanAttempt.Refused -> throw LinkClosedException(lastOpenFailure ?: DisconnectCause.UpdateFirmware(null, DeviceUuids.PROTOCOL_VERSION))
                LanAttempt.Skipped -> Unit
            }
        }
        if (link == null) {
            if (prefer == LinkPreference.LAN) throw LinkClosedException(lastOpenFailure ?: DisconnectCause.Unreachable)
            link = try {
                if (background == null) transportFactory.ble(address) else transportFactory.bleUnattended(address, background.bleConnectTimeoutMs)
            } catch (e: TransportUnavailableException) {
                // The radio, adapter or permission is missing. Retrying cannot help until the user acts.
                throw LinkClosedException(DisconnectCause.Unavailable(e.kind), e)
            }
        }
        val opened = try {
            openSession(link, address, name, fake = transportFactory.isFake(link), watch = false)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { closeSessionLocked() }
            throw e
        }
        val session = _session.value
        if (!opened || session == null) {
            closeSessionLocked()
            throw LinkClosedException(lastOpenFailure ?: DisconnectCause.Rejected("verification failed"))
        }
        lendOwned = session.takeUnless { background == null && uiConnectionWanted.get() }
        return session
    }

    /** One lend came back. The last one closes a session that only collections used. */
    private fun returnLend(session: DeviceSession) {
        if (lends.decrementAndGet() > 0) return
        scope.launch(NonCancellable) {
            connectMutex.withLock {
                // A new lend taken meanwhile holds the mutex to count itself, so this sees it.
                if (lends.get() == 0 && _session.value === session) {
                    if (lendOwned === session) {
                        closeSessionLocked()
                    } else if (uiConnectionWanted.get() && session.isUsable && connectionIntent.owns(uiClaim)) {
                        watchLink(session, session.address, session.transport.displayName, transportFactory.isFake(session.transport), uiClaim, uiPreference)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- link watch

    // Job handles below and in the sync section are read and written only under
    // `stateLock.withLock`, so a cancel from the notification's Stop (another thread) always sees
    // the job that is about to run or is running, never a stale or half-published one.
    private var linkWatchJob: Job? = null
    private var reconnectJob: Job? = null

    /** Bumped by [cancelReconnect]; a reconnect decided before the bump never starts. */
    private var reconnectGeneration = 0L

    /**
     * Once [session] is Ready, wait for it to stop being Ready while it is still the current
     * session — that is a link lost on its own (LAN socket destroyed by the OS in the background,
     * GATT 133, device reboot), never a [disconnect] (which clears the slot first). Then retry
     * [RECONNECT_ATTEMPTS] times, [RECONNECT_DELAY_MS] apart, with the initial transport preference.
     * AUTO uses LAN when discovered and Bluetooth otherwise; LAN_ONLY cannot use Bluetooth.
     * An active collection owns reconnect and resume itself, so its loss is never retried here.
     */
    private fun watchLink(session: DeviceSession, address: String, name: String?, fake: Boolean, claim: Long, preference: TransportPreference = TransportPreference.AUTO) {
        val watch = scope.launch(start = CoroutineStart.LAZY) {
            // A link that closed on its own counts as lost even while the transport still says Ready.
            val lost = combine(session.connectionState, session.link.state) { transport, link ->
                if (link is LinkState.Closed && transport.isReady) ConnectionState.Failed(link.cause.describe()) else transport
            }.first { !it.isReady && it !is ConnectionState.Connecting && !it.isBusy }
            val generation = stateLock.withLock { reconnectGeneration }
            if (_session.value !== session || !connectionIntent.owns(claim)) return@launch
            val reason = (lost as? ConnectionState.Failed)?.reason ?: "device closed the connection"
            val linkCause = (session.link.state.value as? LinkState.Closed)?.cause
            if (linkCause is DisconnectCause.Auth) {
                // A refused or missing credential repeats on every attempt. Only stale keys ask to pair again.
                Log.w(TAG, "link lost (${linkCause.describe()}); no auto-reconnect")
                connectionFailure.value = BleConnectionFailure.authLossMessage(linkCause, reason)
                return@launch
            }
            if (collectionActive.value) return@launch
            Log.w(TAG, "link lost ($reason); idle auto-reconnect starts")
            val reconnect = scope.launch(start = CoroutineStart.LAZY) { reconnectLoop(address, name, fake, reason, claim, preference) }
            val me = coroutineContext[Job]
            val start = stateLock.withLock {
                // A close replaced this watcher, or a Stop arrived after the loss was seen: no reconnect.
                if (linkWatchJob !== me || reconnectGeneration != generation || _collectionActive.value || !connectionIntent.owns(claim)) {
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
            reconnect.invokeOnCompletion { stateLock.withLock { if (reconnectJob === reconnect) reconnectJob = null } }
            reconnect.start()
        }
        val published = stateLock.withLock {
            if (_collectionActive.value || !connectionIntent.owns(claim)) {
                false
            } else {
                linkWatchJob?.cancel()
                linkWatchJob = watch
                true
            }
        }
        if (published) watch.start() else watch.cancel()
    }

    private suspend fun reconnectLoop(address: String, name: String?, fake: Boolean, reason: String, claim: Long, preference: TransportPreference) {
        try {
            for (attempt in 1..RECONNECT_ATTEMPTS) {
                val allowed = stateLock.withLock {
                    if (!connectionIntent.owns(claim) || _collectionActive.value) {
                        false
                    } else {
                        _reconnecting.value = ReconnectState(attempt, RECONNECT_ATTEMPTS, reason)
                        true
                    }
                }
                if (!allowed) return
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.Reconnecting(attempt, RECONNECT_ATTEMPTS)))
                delay(RECONNECT_DELAY_MS)
                val ok = connectMutex.withLock {
                    if (!connectionIntent.owns(claim) || collectionActive.value) return@withLock null
                    // A newer session appeared meanwhile (user action): stop.
                    val cur = _session.value
                    if (cur != null && cur.isUsable) return@withLock true
                    lastOpenFailure = null
                    val opened = connectLocked(address, name, fake, preference, claim, listAfter = true)
                    if (!connectionIntent.owns(claim)) {
                        closeSessionLocked()
                        null
                    } else if (!opened && BleDisconnectCauses.needsPairingRepair(lastOpenFailure)) {
                        // The bond keys failed. Every later attempt fails the same way and would
                        // clear the repair banner it just set, so stop until the user pairs again.
                        Log.w(TAG, "reconnect stopped, pairing needs repair")
                        null
                    } else {
                        opened
                    }
                }
                if (ok == null) return
                if (ok) {
                    return
                }
            }
            if (connectionIntent.owns(claim)) _events.tryEmit(DeviceEvent.Error(DeviceMessage.ReconnectFailed(RECONNECT_ATTEMPTS)))
        } finally {
            if (connectionIntent.owns(claim)) _reconnecting.value = null
        }
    }

    // ----------------------------------------------------------------- manifest

    fun refreshManifest() {
        val session = interactiveSession() ?: return
        launchCommand { refreshManifestNow(session) }
    }

    /**
     * One `LIST`. The previous listing survives a failure (`ManifestState.failed` keeps `last`): a
     * refresh that dies — link lost, timeout — must not blank the Files screen or null the plan.
     * There is no retry on the same session: a lost link is re-established by [watchLink], whose
     * [connectLocked] issues a fresh `LIST`; a timeout means the device is not answering and a
     * second request would only queue behind the stale reply (the link drains it first).
     */
    private suspend fun refreshManifestNow(session: DeviceSession): ManifestState.Loaded? = manifestMutex.withLock {
        if (_session.value !== session) return@withLock null
        _manifest.update { it.refreshing() }
        try {
            val (files, end) = session.list()
            check(_session.value === session) { "LIST session replaced" }
            ManifestState.Loaded(files, end, clock.nowEpochMs()).also { _manifest.value = it }
        } catch (e: CancellationException) {
            if (_session.value === session) _manifest.update { it.failed("LIST cancelled") }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LIST failed: ${e.message}")
            if (_session.value === session) _manifest.update { it.failed(e.message ?: "LIST failed") }
            null
        }
    }

    /** Stop an idle UI reconnect. Collection retries belong to CollectionController. */
    fun cancelReconnect() {
        val job = stateLock.withLock {
            reconnectGeneration++
            reconnectJob.also { reconnectJob = null }
        }
        job?.cancel()
        _reconnecting.value = null
    }

    /** True when a session to [address] is Ready right now. */
    fun isConnectedTo(address: String): Boolean = _session.value?.let { it.address == address && it.isUsable } == true

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

    /** Log the archive's timeline once per connect or collection, and say when readings have no time. */
    private suspend fun reportArchiveTimeline(thing: String) {
        val summary = summarizeArchive(archivedFileDao.forThing(thing))
        if (_info.value?.thing != thing) return
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

    /** A worker-owned session can be observed, but interactive commands cannot take it over. */
    private fun interactiveSession(): DeviceSession? {
        if (collectionActive.value) {
            _events.tryEmit(DeviceEvent.Info(if (backgroundOwner.value != null) DeviceMessage.BackgroundOwnsCommand else DeviceMessage.Busy))
            return null
        }
        return _session.value
    }

    // ------------------------------------------------------------ operation gate

    /**
     * Take the command slot. Commands and collection reservations are mutually exclusive.
     * Check and set are one atomic step.
     */
    private fun tryBeginCommand(): Boolean = stateLock.withLock {
        if (_busy.value || _collectionActive.value) return false
        _busy.value = true
        true
    }

    private fun endCommand() {
        stateLock.withLock { _busy.value = false }
    }

    /** Reserve commands and link replacement before starting the shared controller. */
    internal fun reserveCollection(owner: String?): Boolean {
        val handoff = stateLock.withLock {
            if (_busy.value || _collectionActive.value || lends.get() != 0 || lending) return false
            if (owner != null && (_session.value != null || connectJob?.isCompleted == false || reconnectJob?.isCompleted == false || collectionHandoff.any { !it.isCompleted })) return false
            _collectionActive.value = true
            _backgroundOwner.value = owner
            reconnectGeneration++
            val claim = connectionIntent.claim()
            if (uiConnectionWanted.get()) uiClaim = claim
            (collectionHandoff + listOfNotNull(connectJob, reconnectJob, linkWatchJob)).distinct().also {
                collectionHandoff = it
                connectJob = null
                reconnectJob = null
                linkWatchJob = null
                _reconnecting.value = null
            }
        }
        handoff.forEach { it.cancel() }
        return true
    }

    internal fun releaseCollection(owner: String?) {
        val released = stateLock.withLock {
            if (_collectionActive.value && _backgroundOwner.value == owner) {
                _backgroundOwner.value = null
                _collectionActive.value = false
                true
            } else {
                false
            }
        }
        if (released) {
            scope.launch {
                connectMutex.withLock {
                    val session = _session.value ?: return@withLock
                    if (collectionActive.value || lends.get() != 0) return@withLock
                    if (!session.isUsable) {
                        // The run ended on a link that died, and nothing watches it now. Clear it,
                        // or the device stays "owned" and every background worker skips it.
                        closeSessionLocked()
                    } else if (uiConnectionWanted.get() && connectionIntent.owns(uiClaim)) {
                        watchLink(session, session.address, session.transport.displayName, transportFactory.isFake(session.transport), uiClaim, uiPreference)
                    }
                }
            }
        }
    }

    internal fun publishProgress(progress: SyncProgress) {
        _sync.value = progress
    }

    internal fun emitCollectionEvent(event: DeviceEvent) {
        _events.tryEmit(event)
    }

    internal fun collectionListing() {
        _manifest.update { it.refreshing() }
    }

    internal fun collectionListed(session: DeviceSession, listing: SyncListing) {
        if (_session.value !== session) return
        if (listing.whole) {
            _manifest.value = ManifestState.Loaded(listing.files, listing.end, clock.nowEpochMs())
        } else {
            _manifest.update { it.withNew(listing.files, listing.end) }
        }
    }

    /**
     * A collection ended on a missing bond. Keeps the repair banner after its session closed and
     * says so once, an open failure that already reported it is not repeated.
     */
    internal fun collectionNeedsPairingRepair() {
        if (connectionFailure.getAndUpdate { BleConnectionFailure.PAIRING_REPAIR_MESSAGE } == BleConnectionFailure.PAIRING_REPAIR_MESSAGE) return
        _events.tryEmit(DeviceEvent.Error(DeviceMessage.PairingRepair))
    }

    internal fun collectionListFailed() {
        if (_manifest.value.isLoading) _manifest.update { it.failed("collection LIST ended") }
    }

    /**
     * Finalization runs while the collection still owns its protection and link, so only the manifest
     * refresh, which needs the link, runs here. The derive and the timeline report run after it in
     * [scope], since a derive waits for any backfill in flight and must never hold the sensor (#125).
     */
    internal suspend fun collectionFinished(refresh: Boolean, startedMs: Long) {
        val session = _session.value
        if (refresh && session?.isUsable == true) refreshManifestNow(session)
        scope.launch {
            suspendRunCatching {
                if (refresh) historyDeriver.deriveAfterSync(startedMs)
                _info.value?.thing?.let { reportArchiveTimeline(it) }
            }.onFailure { Log.w(TAG, "derive after collection failed (${it::class.simpleName})") }
        }
    }

    /** Drain queued last-lend cleanup before handing the endpoint to another owner. */
    internal suspend fun closeReturnedLend() {
        connectMutex.withLock {
            if (lends.get() == 0 && _session.value === lendOwned && lendOwned != null) closeSessionLocked()
        }
    }

    private fun refuseBusy() {
        _events.tryEmit(DeviceEvent.Info(DeviceMessage.Busy))
    }

    /** Claim before scheduling so collection admission cannot race a pending UI request. */
    private fun launchCommand(action: suspend () -> Unit) {
        if (!tryBeginCommand()) return refuseBusy()
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                action()
            } finally {
                endCommand()
            }
        }
    }

    // ----------------------------------------------------------------- controls

    /** Automatic SET_TIME with the phone UTC, also preserving the acknowledged reconciliation anchor. */
    fun setTimeFromPhone() {
        val session = interactiveSession() ?: return
        launchCommand { setTimeFromPhoneNow(session, quiet = false) }
    }

    private suspend fun setTimeFromPhoneNow(session: DeviceSession, quiet: Boolean, automatic: Boolean = false): Boolean {
        return try {
            val r = if (automatic) session.synchronizeClockIfNeeded(clock::nowEpochMs) else session.synchronizeClock(clock::nowEpochMs)
            check(_session.value === session) { "clock session replaced" }
            if (r == null) {
                recordDiagnostic("clock", "external clock remains usable; SET_TIME skipped")
                return true
            }
            val info = (session.link.state.value as? LinkState.Ready)?.info?.info
            val thing = info?.thing
            val boot = info?.boot
            if (thing != null && boot != null) {
                historyDeriver.persistClockAnchor(thing, boot, r.epochNanos, r.monotonicMicros)
                // The re-dating derive waits for any History pass in flight, so it runs off the link (#125).
                scope.launch {
                    suspendRunCatching { historyDeriver.deriveThing(thing) }
                        .onFailure { Log.w(TAG, "derive after clock receipt failed (${it::class.simpleName})") }
                }
            }
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
    }

    /** Persist the acknowledged anchor before the controller lists or downloads this boot. */
    internal suspend fun collectionClockSet(session: DeviceSession, result: Frame.TimeSet) {
        check(_session.value === session) { "clock session replaced" }
        val info = verifiedSessionInfo?.takeIf { it.first === session }?.second ?: return
        val thing = info.thing ?: return
        val boot = info.boot ?: return
        historyDeriver.persistClockAnchor(thing, boot, result.epochNanos, result.monotonicMicros)
    }

    /** FLUSH: finalize the RAM batch into a short file so it can be pulled now. */
    fun flush() {
        val session = interactiveSession() ?: return
        launchCommand {
            try {
                val r = session.flush()
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.Finalized(r.rows, r.filesFinalized)))
                refreshManifestNow(session)
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
        launchCommand { suspendRunCatching { session.requestStatus() } }
    }

    // ---------------------------------------------------------------- v2 controls

    fun refreshConfig() {
        val session = interactiveSession() ?: return
        launchCommand {
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
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                val snap = session.setConfig(patch)
                _config.value = snap
                _events.tryEmit(DeviceEvent.Info(DeviceMessage.ConfigSaved(change, snap.rebootRequired)))
            } catch (_: SecretNeedsBluetoothException) {
                // The session is the plaintext LAN socket. Nothing was written (#155).
                _events.tryEmit(DeviceEvent.Error(DeviceMessage.SecretNeedsBluetooth))
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
        if (!tryBeginCommand()) throw IllegalStateException("Wait for the current device task to finish")
        try {
            return session.setConfig(patch).also { _config.value = it }
        } finally {
            endCommand()
        }
    }

    suspend fun readLocation(): space.opensensor.data.protocol.LocationConfig? {
        val session = interactiveSession() ?: throw IllegalStateException("Connect to the device first")
        if (!tryBeginCommand()) throw IllegalStateException("link busy")
        try { return recordPlace(session.getLocationPage()) } finally { endCommand() }
    }

    suspend fun writeLocation(patch: ConfigPatch): space.opensensor.data.protocol.LocationConfig? {
        val session = interactiveSession() ?: throw IllegalStateException("Connect to the device first")
        if (!tryBeginCommand()) throw IllegalStateException("link busy")
        try {
            _config.value = session.setConfig(patch)
            return recordPlace(session.getLocationPage())
        } finally { endCommand() }
    }

    /** Keeps the connected Thing's Discover pin in step with the location [page] it returned. */
    private suspend fun recordPlace(page: space.opensensor.data.protocol.DeviceConfig?): space.opensensor.data.protocol.LocationConfig? {
        val thing = _info.value?.thing
        if (page != null && thing != null) places.recordFrom(thing, page, clock.nowEpochMs())
        return page?.location
    }

    fun startWifiScan() {
        val session = interactiveSession() ?: return
        if (_wifiScan.value is WifiScanState.Scanning) return
        launchCommand {
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
        if (!tryBeginCommand()) return refuseBusy()
        val address = session.address
        val name = session.transport.displayName
        val fake = isFake
        val claim = connectionIntent.claim()
        uiClaim = claim
        uiPreference = TransportPreference.AUTO
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
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
        launchCommand {
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
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
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
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
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
        if (!tryBeginCommand()) return@withContext refuseBusy()
        try {
            uiConnectionWanted.set(false)
            val claim = connectionIntent.claim()
            cancelPendingConnect()
            cancelReconnect()
            connectMutex.withLock {
                if (!connectionIntent.owns(claim)) return@withLock
                // Revoke the live session before deleting credentials and enrollment.
                closeSessionLocked()
                tokenPolicy.clear(address)
                val known = knownDeviceDao.get(address)
                known?.companionAssociationId?.let { companionPresence.disassociate(address, it) }
                // No cell of a forgotten Thing stays on the phone, from any of its epochs.
                (listOfNotNull(known?.thing) + thingEpochDao.forDevice(address).map { it.thing }).distinct().forEach { places.remove(it) }
                knownDeviceDao.delete(address)
            }
        } finally {
            endCommand()
        }
    }

    companion object {
        private const val TAG = "DeviceConnection"

        /** A log label without template content. */
        private fun DescriptorOutcome.label(): String = when (this) {
            is DescriptorOutcome.Fetched -> "fetched"
            is DescriptorOutcome.Rejected -> "rejected ($reason)"
            DescriptorOutcome.NoDescriptor -> "not served"
            DescriptorOutcome.Known -> "known"
            DescriptorOutcome.Unsupported -> "unsupported"
        }

        /**
         * Bound on one [lendLink]. The link open (with a passkey typed on the phone) has
         * [LINK_OPEN_TIMEOUT], the rest covers verification and waiting for a UI connect to finish.
         */
        val LEND_TIMEOUT: Duration = LINK_OPEN_TIMEOUT + 60.seconds

        /** Boot + BLE stack start + advertising on the CoreS3; generous. */
        const val REBOOT_RECONNECT_DELAY_MS = 6_000L

        /** Auto-reconnect after a lost link: bounded, then the user is told. */
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 5_000L

        /** Unattended BLE connect budget: a logger that is not in range should cost seconds per period, not 30 s. */
        const val BACKGROUND_BLE_CONNECT_MS = 15_000L
    }
}
