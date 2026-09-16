package earth.walkthru.aqsync.data

import android.util.Log
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.LiveSample
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<DeviceSession?>(null)
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = _session
        .flatMapLatest { it?.connectionState ?: flowOf(ConnectionState.Disconnected) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

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

    /** Connect to [address] (or the fake device when [fake]). Safe to call while connected to the same peer. */
    fun connect(address: String, name: String?, fake: Boolean = false) {
        scope.launch {
            connectMutex.withLock {
                val existing = _session.value
                if (existing != null && existing.address == address && existing.connectionState.value.isReady) return@withLock
                closeSessionLocked()

                val transport: AqTransport = if (fake) transportFactory.fake(address) else transportFactory.ble(address)
                val session = DeviceSession(transport).also { it.staleFrameLogger = { m -> Log.w(TAG, m) } }
                _session.value = session
                _info.value = null
                _status.value = null
                _live.value = null
                _manifest.value = ManifestState.Idle
                _sync.value = SyncProgress()

                knownDeviceDao.insertIfAbsent(
                    KnownDevice(address, name, null, null, null, System.currentTimeMillis(), null)
                )
                try {
                    transport.connect()
                } catch (e: TransportException) {
                    _events.tryEmit(DeviceEvent.Error(e.message ?: "connection failed"))
                    return@withLock
                }
                startNotificationCollection(session)
                try {
                    val info = session.readInfo()
                    _info.value = info
                    if (info.proto != null && info.proto != 1) {
                        _events.tryEmit(DeviceEvent.Error("Device speaks protocol v${info.proto}; this app implements v1."))
                    }
                    val now = System.currentTimeMillis()
                    knownDeviceDao.upsert(
                        KnownDevice(
                            address = address,
                            name = name ?: transport.displayName,
                            station = info.station,
                            deviceId = info.dev,
                            firmware = info.fw,
                            lastSeenEpochMs = now,
                            lastConnectedEpochMs = now,
                        )
                    )
                    runCatching { session.readStatus() }.onSuccess { _status.value = TimedStatus(it, System.currentTimeMillis()) }
                    runCatching { session.readLive() }.onSuccess { _live.value = TimedLive(it, System.currentTimeMillis()) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _events.tryEmit(DeviceEvent.Error("Connected, but reading `info` failed: ${e.message}"))
                }
                refreshManifest()
            }
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
    }
}
