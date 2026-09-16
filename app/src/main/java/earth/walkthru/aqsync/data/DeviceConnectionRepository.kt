package earth.walkthru.aqsync.data

import android.util.Log
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
import earth.walkthru.aqsync.data.lan.LanConnectException
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.lan.LanDiscovery
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.protocol.AqUuids
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.hexToBytes
import earth.walkthru.aqsync.data.protocol.toHex
import earth.walkthru.aqsync.data.sync.ArchivedIdentity
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileProgress
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.data.sync.SyncPlan
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A `live` sample with the wall-clock instant it arrived, for the freshness indicator. */
data class TimedLive(val sample: LiveSample, val receivedAtEpochMs: Long)

data class TimedStatus(val status: DeviceStatus, val receivedAtEpochMs: Long)

sealed interface ManifestState {
    data object Idle : ManifestState
    data object Loading : ManifestState
    data class Loaded(val files: List<Frame.File>, val end: Frame.ListEnd, val atEpochMs: Long) : ManifestState
    data class Error(val message: String) : ManifestState
}

data class SyncProgress(
    val running: Boolean = false,
    val current: FileProgress? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val outcomes: List<FileOutcome> = emptyList(),
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    val failures: List<FileOutcome> get() = outcomes.filter { it !is FileOutcome.Synced }
}

/** One-shot messages for snackbars. */
sealed interface DeviceEvent {
    data class Info(val message: String) : DeviceEvent
    data class Error(val message: String) : DeviceEvent
}

/** Result of the last `WIFI_SCAN`, for the Device screen. */
sealed interface WifiScanState {
    data object Idle : WifiScanState
    data object Scanning : WifiScanState
    data class Done(val accessPoints: List<Frame.WifiAp>, val atEpochMs: Long) : WifiScanState
    data class Failed(val message: String, val unavailable: Boolean) : WifiScanState
}

/** Result of the last `LOG_TAIL`, for Advanced → Debug. */
sealed interface LogTailState {
    data object Idle : LogTailState
    data object Loading : LogTailState
    data class Loaded(val tail: LogTail, val atEpochMs: Long) : LogTailState
    data class Failed(val message: String) : LogTailState
}

/** How the repository should pick a transport for a connect. */
enum class TransportPreference { AUTO, BLE_ONLY }

/**
 * Owns the single live connection to a logger and everything derived from it.
 *
 * Process-scoped (Hilt singleton with its own [CoroutineScope]) so a download keeps running
 * through screen rotation and navigation; ViewModels only observe and forward user intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DeviceConnectionRepository @Inject constructor(
    private val transportFactory: TransportFactory,
    private val knownDeviceDao: KnownDeviceDao,
    private val archivedFileDao: ArchivedFileDao,
    private val archive: FileArchive,
    private val syncEngine: SyncEngine,
    private val lanDiscovery: LanDiscovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<DeviceSession?>(null)
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = _session
        .flatMapLatest { it?.connectionState ?: flowOf(ConnectionState.Disconnected) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /** Which pipe the current session uses, for the "Bluetooth" / "Wi-Fi LAN" chip. */
    val activeTransport: StateFlow<TransportKind?> = _session
        .map { it?.transport?.kind }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _config = MutableStateFlow<ConfigSnapshot?>(null)
    val config: StateFlow<ConfigSnapshot?> = _config.asStateFlow()

    private val _wifiScan = MutableStateFlow<WifiScanState>(WifiScanState.Idle)
    val wifiScan: StateFlow<WifiScanState> = _wifiScan.asStateFlow()

    private val _logTail = MutableStateFlow<LogTailState>(LogTailState.Idle)
    val logTail: StateFlow<LogTailState> = _logTail.asStateFlow()

    /** Set while a config write / reboot / token fetch is in flight, to disable the buttons. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ------------------------------------------------------------ LAN discovery

    /** `_aqsync._tcp` instances visible right now, keyed by station UUID from their TXT record. */
    private val _lanDevices = MutableStateFlow<Map<String, LanDevice>>(emptyMap())
    val lanDevices: StateFlow<Map<String, LanDevice>> = _lanDevices.asStateFlow()

    private val _lanDiscoveryError = MutableStateFlow<String?>(null)
    val lanDiscoveryError: StateFlow<String?> = _lanDiscoveryError.asStateFlow()

    private var lanDiscoveryJob: Job? = null

    /**
     * Browse mDNS while the app is in the foreground and the LAN permission is held. The caller
     * (the UI gate) is responsible for the permission check; this only starts/stops the browse.
     */
    fun setLanDiscoveryActive(active: Boolean) {
        if (active) {
            if (lanDiscoveryJob?.isActive == true) return
            _lanDiscoveryError.value = null
            lanDiscoveryJob = scope.launch {
                try {
                    lanDiscovery.discover().collect { ev ->
                        when (ev) {
                            is LanDiscovery.Event.Devices -> _lanDevices.value = ev.devices.values
                                .filter { it.isConnectable }
                                .associateBy { it.station!! }
                            is LanDiscovery.Event.Failed -> _lanDiscoveryError.value = ev.message
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _lanDiscoveryError.value = e.message ?: "mDNS browse failed"
                } finally {
                    _lanDevices.value = emptyMap()
                }
            }
        } else {
            lanDiscoveryJob?.cancel()
            lanDiscoveryJob = null
            _lanDevices.value = emptyMap()
        }
    }

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _status = MutableStateFlow<TimedStatus?>(null)
    val status: StateFlow<TimedStatus?> = _status.asStateFlow()

    private val _live = MutableStateFlow<TimedLive?>(null)
    val live: StateFlow<TimedLive?> = _live.asStateFlow()

    private val _manifest = MutableStateFlow<ManifestState>(ManifestState.Idle)
    val manifest: StateFlow<ManifestState> = _manifest.asStateFlow()

    private val _sync = MutableStateFlow(SyncProgress())
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    /** Address of the device we are connected to / connecting to. */
    val currentAddress: String? get() = _session.value?.address
    val isFake: Boolean get() = _session.value?.transport?.let(transportFactory::isFake) == true

    /**
     * Manifest joined with the archive: what is synced, what is missing, what is mid-transfer.
     * Recomputed whenever the manifest, the archive table or the running transfer changes.
     */
    val plan: StateFlow<SyncPlan?> = combine(_manifest, _info, archivedFileDao.observeAll(), _sync) { m, info, archived, sync ->
        val station = info?.station ?: return@combine null
        val loaded = m as? ManifestState.Loaded ?: return@combine null
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
    private val connectMutex = Mutex()

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
        scope.launch {
            connectMutex.withLock {
                val existing = _session.value
                if (existing != null && existing.address == address && existing.connectionState.value.isReady) return@withLock
                closeSessionLocked()
                resetDerivedState()

                knownDeviceDao.insertIfAbsent(
                    KnownDevice(address, name, null, null, null, System.currentTimeMillis(), null)
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
                if (!openSession(transport, address, name)) return@withLock
                refreshManifest()
            }
        }
    }

    /** Attempt a LAN session; null when not applicable or when it failed (the caller falls back to BLE). */
    private suspend fun tryLan(address: String, name: String?, known: KnownDevice?): AqTransport? {
        val station = known?.station ?: return null
        val tokenHex = known.lanToken ?: return null
        val seen = _lanDevices.value[station] ?: return null
        val host = seen.host ?: return null
        val token = try {
            tokenHex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stored LAN token is malformed; ignoring it")
            return null
        }
        val port = seen.port.takeIf { it in 1..65535 } ?: known.lanPort ?: return null
        val transport = transportFactory.lan(address, name, host, port, token)
        // Put the LAN attempt in the session slot so the UI shows "Authenticating on the LAN…".
        val probe = DeviceSession(transport).also { it.staleFrameLogger = { m -> Log.w(TAG, m) } }
        _session.value = probe
        return try {
            transport.connect()
            transport
        } catch (e: LanConnectException) {
            Log.w(TAG, "LAN connect failed (${e.failure}); falling back to Bluetooth")
            _events.tryEmit(DeviceEvent.Info("Wi-Fi LAN: ${e.message}. Using Bluetooth."))
            _session.value = null
            transport.close()
            null
        } catch (e: TransportException) {
            _events.tryEmit(DeviceEvent.Info("Wi-Fi LAN failed (${e.message}). Using Bluetooth."))
            _session.value = null
            transport.close()
            null
        }
    }

    private fun resetDerivedState() {
        _info.value = null
        _status.value = null
        _live.value = null
        _manifest.value = ManifestState.Idle
        _sync.value = SyncProgress()
        _config.value = null
        _wifiScan.value = WifiScanState.Idle
        _logTail.value = LogTailState.Idle
    }

    /** Connect [transport] (if not already), read `info`, persist identity, fetch token/config. */
    private suspend fun openSession(transport: AqTransport, address: String, name: String?): Boolean {
        val session = (_session.value?.takeIf { it.transport === transport } ?: DeviceSession(transport))
            .also { it.staleFrameLogger = { m -> Log.w(TAG, m) } }
        _session.value = session
        try {
            transport.connect()
        } catch (e: TransportException) {
            _events.tryEmit(DeviceEvent.Error(e.message ?: "connection failed"))
            return false
        }
        startNotificationCollection(session)
        try {
            val info = session.readInfo()
            _info.value = info
            val proto = info.proto
            if (proto != null && proto > AqUuids.PROTOCOL_VERSION) {
                _events.tryEmit(DeviceEvent.Info("Device speaks protocol v$proto; this app implements v${AqUuids.PROTOCOL_VERSION}. Newer features are ignored."))
            }
            val now = System.currentTimeMillis()
            val previous = knownDeviceDao.get(address)
            knownDeviceDao.upsert(
                KnownDevice(
                    address = address,
                    name = name ?: transport.displayName ?: previous?.name,
                    station = info.station,
                    deviceId = info.dev,
                    firmware = info.fw,
                    lastSeenEpochMs = now,
                    lastConnectedEpochMs = now,
                    lanToken = previous?.lanToken,
                    lanPort = previous?.lanPort,
                )
            )
            runCatching { session.readStatus() }.onSuccess { _status.value = TimedStatus(it, System.currentTimeMillis()) }
            runCatching { session.readLive() }.onSuccess { _live.value = TimedLive(it, System.currentTimeMillis()) }

            val v2 = (proto ?: 1) >= 2
            if (v2) {
                // Token: once, over BLE only, when we do not hold one yet. Never logged.
                if (transport.kind != TransportKind.LAN && previous?.lanToken == null) {
                    fetchTokenLocked(session, address, quiet = true)
                }
                runCatching { session.getConfig() }
                    .onSuccess { _config.value = it }
                    .onFailure { Log.w(TAG, "GET_CONFIG failed: ${it.message}") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _events.tryEmit(DeviceEvent.Error("Connected, but reading `info` failed: ${e.message}"))
        }
        return true
    }

    private suspend fun fetchTokenLocked(session: DeviceSession, address: String, quiet: Boolean): Boolean {
        return try {
            val t = session.getToken()
            knownDeviceDao.setLanToken(address, t.token.toHex(), t.port)
            if (!quiet) _events.tryEmit(DeviceEvent.Info("LAN token stored on this phone (port ${t.port})."))
            true
        } catch (e: NotAllowedOnLinkException) {
            if (!quiet) _events.tryEmit(DeviceEvent.Error("The token can only be fetched over Bluetooth."))
            false
        } catch (e: DeviceErrorException) {
            if (!quiet) _events.tryEmit(DeviceEvent.Error(e.message ?: "GET_TOKEN rejected"))
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET_TOKEN failed: ${e.message}")
            if (!quiet) _events.tryEmit(DeviceEvent.Error("GET_TOKEN failed: ${e.message}"))
            false
        }
    }

    private fun startNotificationCollection(session: DeviceSession) {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            launch { session.status.collect { _status.value = TimedStatus(it, System.currentTimeMillis()) } }
            launch { session.live.collect { _live.value = TimedLive(it, System.currentTimeMillis()) } }
        }
    }

    fun disconnect() {
        scope.launch { connectMutex.withLock { closeSessionLocked() } }
    }

    private suspend fun closeSessionLocked() {
        syncJob?.cancel()
        syncJob = null
        notificationJob?.cancel()
        notificationJob = null
        _session.value?.let { s ->
            runCatching { s.transport.disconnect() }
            s.transport.close()
        }
        _session.value = null
        _sync.update { it.copy(running = false, current = null) }
    }

    // ----------------------------------------------------------------- manifest

    fun refreshManifest() {
        val session = _session.value ?: return
        scope.launch {
            _manifest.value = ManifestState.Loading
            try {
                val (files, end) = session.list()
                _manifest.value = ManifestState.Loaded(files, end, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _manifest.value = ManifestState.Error(e.message ?: "LIST failed")
            }
        }
    }

    // --------------------------------------------------------------------- sync

    /** Download every file the plan marks as missing or partial. */
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
        syncJob?.cancel()
    }

    private fun startSync(entries: List<ManifestEntry>) {
        if (_sync.value.running) return
        val session = _session.value ?: return
        val info = _info.value
        val station = info?.station ?: run {
            _events.tryEmit(DeviceEvent.Error("Cannot sync: device `info` has no station id"))
            return
        }
        val maxRead = info.maxRead ?: DEFAULT_MAX_READ
        if (entries.isEmpty()) {
            _events.tryEmit(DeviceEvent.Info("Archive is already up to date"))
            return
        }
        val total = entries.sumOf { it.bytes - it.partialBytes }
        val started = System.currentTimeMillis()
        _sync.value = SyncProgress(running = true, filesTotal = entries.size, bytesTotal = total, startedAtEpochMs = started)
        val meter = RateMeter()

        syncJob = scope.launch {
            var bytesBefore = 0L
            val outcomes = ArrayList<FileOutcome>()
            try {
                for (entry in entries) {
                    val fileBase = bytesBefore
                    val outcome = syncEngine.download(
                        session = session,
                        station = station,
                        sourceDevice = session.address,
                        name = entry.name,
                        expectedBytes = entry.bytes,
                        maxRead = maxRead,
                    ) { p ->
                        val done = fileBase + (p.bytesDone - p.resumedFrom)
                        _sync.update {
                            it.copy(current = p, bytesDone = done, bytesPerSecond = meter.update(done))
                        }
                    }
                    bytesBefore += entry.bytes - entry.partialBytes
                    outcomes += outcome
                    _sync.update { it.copy(filesDone = outcomes.size, outcomes = outcomes.toList(), bytesDone = bytesBefore) }
                    if (outcome !is FileOutcome.Synced) {
                        _events.tryEmit(DeviceEvent.Error("${entry.name.substringAfterLast('/')}: ${describe(outcome)}"))
                    }
                }
                val synced = outcomes.count { it is FileOutcome.Synced }
                _events.tryEmit(DeviceEvent.Info("Synced $synced of ${entries.size} file(s)"))
            } catch (e: CancellationException) {
                _events.tryEmit(DeviceEvent.Info("Sync stopped; partial files are kept for resume"))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sync aborted", e)
                _events.tryEmit(DeviceEvent.Error("Sync aborted: ${e.message ?: e::class.simpleName}"))
            } finally {
                _sync.update { it.copy(running = false, current = null, finishedAtEpochMs = System.currentTimeMillis()) }
                // A FLUSH or a rotation may have finalized a new file while we were pulling.
                if (session.connectionState.value.isReady) refreshManifest()
            }
        }
    }

    private fun describe(o: FileOutcome): String = when (o) {
        is FileOutcome.Synced -> "synced"
        is FileOutcome.Mismatch -> o.reason
        is FileOutcome.DeviceError -> o.message
        is FileOutcome.Failed -> o.message
    }

    // ----------------------------------------------------------------- controls

    /** SET_TIME with the phone's clock. Only rows sampled afterwards get UTC (contract). */
    fun setTimeFromPhone() {
        val session = _session.value ?: return
        scope.launch {
            try {
                val epoch = System.currentTimeMillis() / 1000
                val r = session.setTime(epoch)
                _events.tryEmit(DeviceEvent.Info("Device clock set (epoch ${r.epochSeconds}). Rows from now on carry UTC; earlier rows stay in `unsynced`."))
                runCatching { session.readStatus() }.onSuccess { _status.value = TimedStatus(it, System.currentTimeMillis()) }
            } catch (e: DeviceErrorException) {
                _events.tryEmit(DeviceEvent.Error(e.message ?: "SET_TIME rejected"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("SET_TIME failed: ${e.message}"))
            }
        }
    }

    /** FLUSH: finalize the RAM batch into a short file so it can be pulled now. */
    fun flush() {
        val session = _session.value ?: return
        scope.launch {
            try {
                val r = session.flush()
                _events.tryEmit(DeviceEvent.Info("Finalized ${r.rows} buffered row(s); ${r.filesFinalized} files on the card"))
                refreshManifest()
            } catch (e: DeviceErrorException) {
                _events.tryEmit(DeviceEvent.Error(e.message ?: "FLUSH rejected"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("FLUSH failed: ${e.message}"))
            }
        }
    }

    fun requestStatus() {
        val session = _session.value ?: return
        scope.launch { runCatching { session.requestStatus() } }
    }

    // ---------------------------------------------------------------- v2 controls

    fun refreshConfig() {
        val session = _session.value ?: return
        scope.launch {
            try {
                _config.value = session.getConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("GET_CONFIG failed: ${e.message}"))
            }
        }
    }

    /**
     * `SET_CONFIG`. Validation already happened in [ConfigPatch]; `ERROR` 12 names the key the
     * device still rejected. A `ble.*` change is reported as pending until the next boot.
     */
    fun applyConfig(patch: ConfigPatch, successMessage: String? = null) {
        val session = _session.value ?: return
        if (patch.isEmpty) return
        scope.launch {
            _busy.value = true
            try {
                val snap = session.setConfig(patch)
                _config.value = snap
                val msg = successMessage ?: "Settings saved"
                _events.tryEmit(
                    DeviceEvent.Info(
                        if (snap.rebootRequired) "$msg. Bluetooth changes apply after a reboot." else msg
                    )
                )
            } catch (e: InvalidConfigException) {
                _events.tryEmit(DeviceEvent.Error("Device rejected `${e.key}`; nothing was changed."))
            } catch (e: DeviceErrorException) {
                _events.tryEmit(DeviceEvent.Error(e.message ?: "SET_CONFIG rejected"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("SET_CONFIG failed: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun startWifiScan() {
        val session = _session.value ?: return
        if (_wifiScan.value is WifiScanState.Scanning) return
        scope.launch {
            _wifiScan.value = WifiScanState.Scanning
            try {
                val r = session.wifiScan()
                _wifiScan.value = WifiScanState.Done(r.accessPoints.sortedByDescending { it.rssi }, System.currentTimeMillis())
            } catch (e: WifiUnavailableException) {
                _wifiScan.value = WifiScanState.Failed("The device's Wi-Fi radio cannot scan right now.", unavailable = true)
            } catch (e: DeviceErrorException) {
                _wifiScan.value = WifiScanState.Failed(e.message ?: "WIFI_SCAN rejected", unavailable = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _wifiScan.value = WifiScanState.Failed("WIFI_SCAN failed: ${e.message}", unavailable = false)
            }
        }
    }

    fun clearWifiScan() {
        if (_wifiScan.value !is WifiScanState.Scanning) _wifiScan.value = WifiScanState.Idle
    }

    /**
     * `REBOOT` → `REBOOTING delay_ms`, then drop the link and reconnect once the device is back
     * (BLE advertising resumes a few seconds after boot; the same transport rule applies again).
     */
    fun rebootAndReconnect() {
        val session = _session.value ?: return
        val address = session.address
        val name = session.transport.displayName
        val fake = isFake
        scope.launch {
            _busy.value = true
            try {
                val r = session.reboot()
                _events.tryEmit(DeviceEvent.Info("Device is rebooting (in ${r.delayMs} ms). Reconnecting…"))
                delay(r.delayMs.toLong().coerceIn(100L, 5_000L))
                connectMutex.withLock { closeSessionLocked() }
                delay(REBOOT_RECONNECT_DELAY_MS)
                connect(address, name, fake = fake)
            } catch (e: DeviceErrorException) {
                _events.tryEmit(DeviceEvent.Error(e.message ?: "REBOOT rejected"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("REBOOT failed: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshLogTail(maxBytes: Int = DeviceSession.LOG_RING_BYTES) {
        val session = _session.value ?: return
        if (_logTail.value is LogTailState.Loading) return
        scope.launch {
            _logTail.value = LogTailState.Loading
            try {
                _logTail.value = LogTailState.Loaded(session.logTail(maxBytes), System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _logTail.value = LogTailState.Failed(e.message ?: "LOG_TAIL failed")
            }
        }
    }

    /** Explicit "Fetch token" (BLE only by contract). */
    fun fetchToken() {
        val session = _session.value ?: return
        if (session.transport.kind == TransportKind.LAN) {
            _events.tryEmit(DeviceEvent.Error("The token can only be fetched over Bluetooth. Reconnect over Bluetooth first."))
            return
        }
        scope.launch {
            _busy.value = true
            try {
                fetchTokenLocked(session, session.address, quiet = false)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * `lan.rotate_token=1`: the device forgets the old token and drops every LAN session, so the
     * stored copy is cleared and — over BLE — the new one is fetched right away.
     */
    fun rotateToken() {
        val session = _session.value ?: return
        val address = session.address
        scope.launch {
            _busy.value = true
            try {
                _config.value = session.setConfig(ConfigPatch.build { rotateToken() })
                knownDeviceDao.setLanToken(address, null, null)
                if (session.transport.kind == TransportKind.LAN) {
                    _events.tryEmit(DeviceEvent.Info("Token rotated. This LAN session will drop; reconnect over Bluetooth to fetch the new token."))
                } else if (fetchTokenLocked(session, address, quiet = true)) {
                    _events.tryEmit(DeviceEvent.Info("Token rotated and the new one stored on this phone."))
                } else {
                    _events.tryEmit(DeviceEvent.Info("Token rotated. Use “Fetch token” to store the new one."))
                }
            } catch (e: DeviceErrorException) {
                _events.tryEmit(DeviceEvent.Error(e.message ?: "rotate_token rejected"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(DeviceEvent.Error("rotate_token failed: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    /** `ble.clear_bonds=1`: the device forgets every phone, including this one. */
    fun clearBonds() {
        applyConfig(ConfigPatch.build { clearBonds() }, "Device forgot all bonded phones. You will pair again on the next Bluetooth connection.")
    }

    /** Whether the connected device has a stored LAN token (for the LAN card). */
    val currentKnownDevice: Flow<KnownDevice?> = _session.flatMapLatest { s ->
        if (s == null) flowOf(null) else knownDeviceDao.observeAll().map { list -> list.firstOrNull { it.address == s.address } }
    }

    // ------------------------------------------------------------- known devices

    val knownDevices: Flow<List<KnownDevice>> = knownDeviceDao.observeAll()

    suspend fun touchSeen(address: String, name: String?) = withContext(Dispatchers.IO) {
        knownDeviceDao.touch(address, name, System.currentTimeMillis())
    }

    suspend fun forget(address: String) = withContext(Dispatchers.IO) { knownDeviceDao.delete(address) }

    /** Rolling throughput over the last few seconds. */
    private class RateMeter {
        private val samples = ArrayDeque<Pair<Long, Long>>()

        fun update(bytesDone: Long): Double {
            val now = System.nanoTime()
            samples.addLast(now to bytesDone)
            while (samples.size > 2 && now - samples.first().first > WINDOW_NS) samples.removeFirst()
            val (t0, b0) = samples.first()
            val dt = (now - t0) / 1e9
            return if (dt <= 0.0) 0.0 else (bytesDone - b0) / dt
        }

        companion object {
            const val WINDOW_NS = 3_000_000_000L
        }
    }

    companion object {
        private const val TAG = "DeviceConnection"

        /** Used only if `info.max_read` is absent; conservative. */
        const val DEFAULT_MAX_READ = 4096

        /** Boot + BLE stack start + advertising on the CoreS3; generous. */
        const val REBOOT_RECONNECT_DELAY_MS = 6_000L
    }
}
