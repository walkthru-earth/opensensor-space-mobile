package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.CollectionController
import earth.walkthru.aqsync.collection.CollectionFailure
import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.CollectionScope
import earth.walkthru.aqsync.collection.CollectionState
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.collection.Lease
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.LinkProvider
import earth.walkthru.aqsync.collection.PrepareStep
import earth.walkthru.aqsync.collection.StartResult
import earth.walkthru.aqsync.collection.StopSource
import earth.walkthru.aqsync.collection.StorageFailure
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.collection.endReason
import earth.walkthru.aqsync.data.background.AndroidKeepAlive
import earth.walkthru.aqsync.data.ble.BleDisconnectCauses
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.coroutines.ApplicationScope
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.coroutines.MonotonicTime
import earth.walkthru.aqsync.data.coroutines.WallClock
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileProblem
import earth.walkthru.aqsync.data.sync.SyncEndCause
import earth.walkthru.aqsync.link.retryable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock

/** UI and worker entrypoints for one device. CollectionController alone owns its transfer. */
class DeviceFacade internal constructor(
    internal val linkOwner: DeviceLinkOwner,
    private val collector: ArchiveCollector,
    private val keepAlive: KeepAlive,
    private val collectionBudget: CollectionBudget,
    private val admission: CollectionAdmission,
    @ApplicationScope applicationScope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    private val clock: WallClock,
    private val monotonic: MonotonicTime,
) {
    @Inject
    constructor(
        linkOwner: DeviceLinkOwner,
        collector: ArchiveCollector,
        keepAlive: AndroidKeepAlive,
        collectionBudget: CollectionBudget,
        admission: CollectionAdmission,
        @ApplicationScope applicationScope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
        clock: WallClock,
        monotonic: MonotonicTime,
    ) : this(linkOwner, collector, keepAlive as KeepAlive, collectionBudget, admission, applicationScope, io, clock, monotonic)

    private val scope = CoroutineScope(applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]) + io)
    private val lock = ReentrantLock()
    private var address: String? = null
    private var request: Request? = null
    private lateinit var controller: CollectionController
    val collectionState: StateFlow<CollectionState> get() = controller.state
    private val progress = MutableStateFlow(SyncProgress())
    val sync = progress.asStateFlow()
    private val needed = MutableStateFlow(false)
    val foregroundNeeded = needed.asStateFlow()
    private val waiting = MutableStateFlow<ReconnectState?>(null)
    val reconnecting: StateFlow<ReconnectState?> = combine(waiting, linkOwner.reconnecting, linkOwner.collectionActive) { collection, idle, reserved ->
        if (reserved) collection else idle
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Who holds this device for a collection right now: nobody, an interactive run started from
     * the app, or a background worker. Reserved from admission to cleanup, so it stays set while a
     * run waits for its link to come back.
     */
    val ownership: StateFlow<DeviceOwnership> = combine(linkOwner.collectionActive, linkOwner.backgroundOwner, ::deviceOwnership)
        .stateIn(scope, SharingStarted.Eagerly, DeviceOwnership.IDLE)

    /** [ownership] read straight from the link owner, without the flow's dispatch delay. */
    fun ownershipNow(): DeviceOwnership = deviceOwnership(linkOwner.collectionActive.value, linkOwner.backgroundOwner.value)

    /**
     * Free the device for a background worker. Never stops a collection: returns false and leaves
     * everything as it is while any run holds the device. True once no session is open.
     */
    suspend fun releaseForBackground(): Boolean {
        if (ownershipNow() != DeviceOwnership.IDLE) return false
        linkOwner.disconnectNow()
        return linkOwner.session.value == null && ownershipNow() == DeviceOwnership.IDLE
    }

    internal fun bind(address: String) {
        check(this.address == null || this.address == address)
        if (this.address != null) return
        this.address = address
        linkOwner.bind(address)
        controller = CollectionController(
            address,
            LinkProvider { device, preference ->
                val active = lock.withLock { requireNotNull(request) }
                linkOwner.lendLink(device, preference, active.background, forCollection = true)
            },
            collector,
            KeepAlive { kind, intent -> acquireLease(kind, intent) },
            scope,
            permits = collectionBudget.permits,
            time = monotonic,
            clock = clock::nowEpochMs,
            onTerminal = { terminal ->
                publish(terminal)
                notifyTerminal(terminal)
            },
            onOutcome = { intent, outcome ->
                lock.withLock {
                    request?.takeIf { it.intent.id == intent.id }?.let { active ->
                        active.outcomes[outcome.name] = outcome
                        if (outcome is FileOutcome.Synced && !outcome.alreadyArchived) active.newlySynced.putIfAbsent(outcome.name, outcome)
                    }
                }
            },
            onListed = { link, files, end ->
                lock.withLock {
                    val listing = controller.state.value as? CollectionState.Preparing
                    if (listing?.step == PrepareStep.LIST && request?.intent?.id == listing.intentId) {
                        request?.lastListedAttempt = listing.attempt
                        linkOwner.collectionListed(link.session, files, end)
                    }
                }
            },
            onClockSet = { link, result -> linkOwner.collectionClockSet(link.session, result) },
            onCleanup = { linkOwner.closeReturnedLend() },
            onFinalizing = { _, _ ->
                val active = lock.withLock { requireNotNull(request) }
                linkOwner.collectionFinished(refresh = active.intent.origin == CollectionOrigin.USER_TAP, startedMs = active.startedMs)
            },
        )
        scope.launch { controller.state.collect { publish(it) } }
    }

    private fun acquireLease(kind: KeepAliveKind, intent: CollectionIntent): Lease? {
        if (kind == KeepAliveKind.WIFI) return keepAlive.acquire(kind, intent)
        val active = lock.withLock { request?.takeIf { it.intent.id == intent.id } } ?: return null
        val acquired = if (intent.origin == CollectionOrigin.WORKER) {
            active.protection.acquire(kind, intent)
        } else if (keepAlive is AndroidKeepAlive) {
            keepAlive.acquireProcess(intent) { loss ->
                active.loss = loss
                applyLoss(intent.id, loss)
            }
        } else {
            keepAlive.acquire(kind, intent)
        }
        val lease = acquired ?: return null
        return Lease {
            try { lease.close() } finally { releaseRequest(active) }
        }
    }

    private fun applyLoss(intentId: Long, loss: AndroidKeepAlive.ServiceLoss) {
        when (loss) {
            AndroidKeepAlive.ServiceLoss.REFUSED -> controller.keepAliveRefused(KeepAliveKind.PROCESS, intentId)
            AndroidKeepAlive.ServiceLoss.TIMEOUT -> controller.stop(StopSource.FGS_TIMEOUT, intentId)
            AndroidKeepAlive.ServiceLoss.DESTROYED -> controller.stop(StopSource.SYSTEM, intentId)
        }
        publish(controller.state.value)
    }

    private fun releaseRequest(active: Request) {
        publish(controller.state.value)
        val released = lock.withLock {
            if (request !== active) false else { request = null; true }
        }
        if (released) {
            linkOwner.releaseCollection(active.background?.owner)
        }
        active.admission.close()
    }

    private fun start(intent: CollectionIntent, background: BackgroundLinkRequest? = null, protection: KeepAlive = keepAlive): StartReceipt? {
        val admitted = admission.acquire(intent.origin) ?: return null
        val active = Request(intent, background, protection, admitted, clock.nowEpochMs())
        val reserved = lock.withLock {
            val previous = request
            val restarting = previous != null && !controller.state.value.active && previous.background == null && background == null
            if ((previous != null && !restarting) || (previous == null && !linkOwner.reserveCollection(background?.owner))) {
                false
            } else {
                request = active
                true
            }
        }
        if (!reserved) {
            admitted.close()
            return null
        }
        val result = controller.start(intent)
        publish(controller.state.value)
        active.loss?.let { applyLoss(intent.id, it) }
        if (result !is StartResult.Started) releaseRequest(active)
        return StartReceipt(result, active)
    }

    private fun interactive(scope: CollectionScope, clockConfirmed: Boolean) {
        val device = address ?: return
        val intent = CollectionIntent(nextIntent.incrementAndGet(), device, scope, CollectionOrigin.USER_TAP, clockConfirmed)
        if (start(intent) == null) linkOwner.emitCollectionEvent(DeviceEvent.Info(DeviceMessage.BusyBeforeSync))
    }

    /**
     * Sync everything. The clock follows the automatic rule shared with connection setup, so
     * there is no mode or confirmation to pass.
     */
    fun smartSync() = interactive(CollectionScope.All, clockConfirmed = true)
    fun syncAll() = interactive(CollectionScope.All, clockConfirmed = true)
    fun syncOne(name: String) = interactive(CollectionScope.One(name), clockConfirmed = true)
    fun cancelSync() { stop(StopSource.USER) }
    fun stop(by: StopSource, intentId: Long? = null): Boolean = controller.stop(by, intentId).also { publish(controller.state.value) }
    fun cancelReconnect() { stop(StopSource.USER); linkOwner.cancelReconnect() }

    suspend fun syncInBackground(
        address: String,
        lanTarget: LanDevice?,
        bleConnectTimeoutMs: Long = 15_000L,
        owner: String = "background",
        protection: KeepAlive = KeepAlive { _, _ -> null },
        stopSource: () -> StopSource = { StopSource.SYSTEM },
    ): BackgroundSyncResult {
        check(this.address == address)
        val name = currentDisplayName
        val preference = if (lanTarget == null) LinkPreference.BLE else LinkPreference.LAN
        val intent = CollectionIntent(nextIntent.incrementAndGet(), address, CollectionScope.All, CollectionOrigin.WORKER, clockConfirmed = true, linkPreference = preference)
        val receipt = start(intent, BackgroundLinkRequest(lanTarget, bleConnectTimeoutMs, owner), protection)
            ?: return BackgroundSyncResult(address, name, null, 0, 0, 0, skipped = "interactive collection or device ownership")
        try {
            val terminal = if (receipt.result is StartResult.Started) controller.state.first { it.intentId == intent.id && !it.active } else controller.state.value
            // Stop publishes before cleanup; a verified commit can settle while cleanup drains.
            withContext(NonCancellable) { controller.awaitCleanup(intent.id); linkOwner.closeReturnedLend() }
            publish(terminal)
            val outcomes = lock.withLock { receipt.request.outcomes.values.toList() }
            val saved = lock.withLock { receipt.request.newlySynced.values.toList() }
            val settledFailures = outcomes.count { it !is FileOutcome.Synced }
            return when (terminal) {
                is CollectionState.Done -> BackgroundSyncResult(address, currentDisplayName ?: name, if (lanTarget == null) earth.walkthru.aqsync.data.ble.TransportKind.BLE else earth.walkthru.aqsync.data.ble.TransportKind.LAN, saved.size, saved.sumOf { it.bytes }, (terminal.summary.notSaved.map { it.name } + outcomes.filter { it !is FileOutcome.Synced }.map { it.name }).toSet().size)
                is CollectionState.Failed -> {
                    val cause = (terminal.failure as? CollectionFailure.Link)?.cause
                    val absent = cause is earth.walkthru.aqsync.link.DisconnectCause.Unreachable || (cause is earth.walkthru.aqsync.link.DisconnectCause.Timeout && cause.phase == earth.walkthru.aqsync.link.TimeoutPhase.OPEN)
                    val skipped = absent && outcomes.isEmpty()
                    BackgroundSyncResult(address, name, null, saved.size, saved.sumOf { it.bytes }, settledFailures + if (skipped) 0 else 1, skipped = if (skipped) "device unavailable" else null, incomplete = !skipped && cause?.retryable == true)
                }
                else -> {
                    BackgroundSyncResult(address, name, null, saved.size, saved.sumOf { it.bytes }, settledFailures, incomplete = true)
                }
            }
        } catch (e: CancellationException) {
            stop(stopSource(), intent.id)
            throw e
        } finally {
            withContext(NonCancellable) {
                controller.awaitCleanup(intent.id)
                linkOwner.closeReturnedLend()
            }
        }
    }

    private var terminalNotice: Long? = null
    private var progressIntent: Long? = null
    private var lastStep: PrepareStep? = null
    private fun publish(state: CollectionState) = lock.withLock {
        if (state !== controller.state.value) return@withLock
        val active = request
        val interactive = active?.intent?.origin == CollectionOrigin.USER_TAP
        needed.value = state.active && interactive
        waiting.value = (state as? CollectionState.Waiting)?.let { ReconnectState(it.retry, it.retryLimit, "link lost") }
        val old = progress.value
        val next = when (state) {
            CollectionState.Idle -> old
            is CollectionState.Preparing -> {
                lastStep = state.step
                if (state.step == PrepareStep.LIST && active?.lastListedAttempt != state.attempt) linkOwner.collectionListing()
                if (old.running && progressIntent == state.intentId) old else SyncProgress(running = true, startedAtEpochMs = clock.nowEpochMs())
            }
            is CollectionState.Transferring -> old.copy(running = true, endReason = null, current = state.progress.current, filesDone = state.progress.filesDone, filesTotal = state.progress.filesTotal, bytesDone = state.progress.receivedBytes, bytesTotal = state.progress.bytesTotal, bytesPerSecond = state.progress.bytesPerSecond)
            is CollectionState.Waiting, is CollectionState.Finalizing -> old.copy(running = true, endReason = null)
            is CollectionState.Done -> old.copy(running = false, filesDone = state.summary.files, filesTotal = state.summary.files, outcomes = state.summary.outcomes, current = null, finishedAtEpochMs = clock.nowEpochMs(), endCause = SyncEndCause.COMPLETED, endReason = SyncEndReason.COMPLETED)
            is CollectionState.Failed -> old.copy(running = false, current = null, outcomes = active?.outcomes?.values?.toList() ?: old.outcomes, finishedAtEpochMs = clock.nowEpochMs(), endCause = SyncEndCause.FAILURE, endReason = state.failure.endReason())
            is CollectionState.Stopped -> old.copy(running = false, current = null, outcomes = active?.outcomes?.values?.toList() ?: old.outcomes, finishedAtEpochMs = clock.nowEpochMs(), endCause = SyncEndCause.STOPPED, endReason = state.by.endReason())
        }
        progressIntent = state.intentId
        progress.value = next
        linkOwner.publishProgress(next)
    }

    private fun notifyTerminal(state: CollectionState) = lock.withLock {
        if (state.intentId != null && terminalNotice != state.intentId) {
            if (state !is CollectionState.Done && state === controller.state.value) linkOwner.collectionListFailed()
            terminalNotice = state.intentId
            when (state) {
                is CollectionState.Done -> {
                    val summary = state.summary
                    linkOwner.emitCollectionEvent(DeviceEvent.Info(if (summary.outcomes.isEmpty() && summary.notSaved.isEmpty()) DeviceMessage.UpToDate else DeviceMessage.SyncFinished(summary.newFiles, summary.files)))
                    summary.notSaved.forEach {
                        val problem = when (it.problem) {
                            FileProblem.CHECK_FAILED, FileProblem.LOCAL_CONFLICT -> DeviceMessage.FileProblem.CHECK_FAILED
                            FileProblem.REFUSED -> DeviceMessage.FileProblem.DEVICE_REFUSED
                            else -> DeviceMessage.FileProblem.FAILED
                        }
                        linkOwner.emitCollectionEvent(DeviceEvent.Error(DeviceMessage.FileNotSynced(it.name.substringAfterLast('/'), problem)))
                    }
                }
                is CollectionState.Failed -> {
                    val failure = state.failure
                    val shortBytes = (failure as? CollectionFailure.Storage)?.shortBytes
                    if (failure is CollectionFailure.Link && BleDisconnectCauses.needsPairingRepair(failure.cause)) {
                        // Retrying cannot help, the user pairs again. The banner says how.
                        linkOwner.collectionNeedsPairingRepair()
                        return@withLock
                    }
                    val reason = failure.endReason()
                    val message = when {
                        failure is CollectionFailure.Storage && failure.kind == StorageFailure.NO_SPACE && shortBytes != null -> DeviceMessage.NotEnoughSpace(shortBytes)
                        // The open failure already said the firmware must be updated, with versions.
                        reason == SyncEndReason.UPDATE_FIRMWARE -> return@withLock
                        lastStep == PrepareStep.LIST && reason in GENERIC_ENDS -> DeviceMessage.FileListNotReady
                        else -> DeviceMessage.SyncEnded(reason)
                    }
                    linkOwner.emitCollectionEvent(DeviceEvent.Error(message))
                }
                is CollectionState.Stopped -> linkOwner.emitCollectionEvent(
                    DeviceEvent.Info(if (state.by.endReason() == SyncEndReason.STOPPED) DeviceMessage.SyncStopped else DeviceMessage.SyncEnded(SyncEndReason.INTERRUPTED)),
                )
                else -> Unit
            }
        }
    }

    private class StartReceipt(val result: StartResult, val request: Request)

    private class Request(val intent: CollectionIntent, val background: BackgroundLinkRequest?, val protection: KeepAlive, val admission: Lease, val startedMs: Long) {
        var lastListedAttempt: Int? = null
        val outcomes = linkedMapOf<String, FileOutcome>()
        val newlySynced = linkedMapOf<String, FileOutcome.Synced>()

        @Volatile var loss: AndroidKeepAlive.ServiceLoss? = null
    }

    val session get() = linkOwner.session
    val connectionState get() = linkOwner.connectionState
    val activeTransport get() = linkOwner.activeTransport
    val config get() = linkOwner.config
    val wifiScan get() = linkOwner.wifiScan
    val logTail get() = linkOwner.logTail
    val backgroundOwner get() = linkOwner.backgroundOwner
    val busy get() = linkOwner.busy
    val lanDevices get() = linkOwner.lanDevices
    val lanDiscoveryError get() = linkOwner.lanDiscoveryError
    val info get() = linkOwner.info
    val status get() = linkOwner.status
    val statusRaw get() = linkOwner.statusRaw
    val live get() = linkOwner.live
    val manifest get() = linkOwner.manifest
    val archiveTimeSummary get() = linkOwner.archiveTimeSummary
    val syncDiagnostics get() = linkOwner.syncDiagnostics
    val events get() = linkOwner.events
    val currentAddress get() = linkOwner.currentAddress
    val currentDisplayName get() = linkOwner.currentDisplayName
    val isFake get() = linkOwner.isFake
    val plan get() = linkOwner.plan
    val interactiveSessionAddress get() = linkOwner.interactiveSessionAddress
    val currentKnownDevice get() = linkOwner.currentKnownDevice
    val knownDevices get() = linkOwner.knownDevices
    fun connect(address: String, name: String?, fake: Boolean = false, preference: TransportPreference = TransportPreference.AUTO) = linkOwner.connect(address, name, fake, preference)
    fun disconnect() {
        val disconnectClaim = linkOwner.claimDisconnectIntent()
        val intentId = controller.state.value.intentId
        stop(StopSource.APP)
        scope.launch {
            intentId?.let { controller.awaitCleanup(it) }
            linkOwner.disconnectNow(disconnectClaim)
        }
    }
    suspend fun disconnectNow() {
        val disconnectClaim = linkOwner.claimDisconnectIntent()
        val intentId = controller.state.value.intentId
        stop(StopSource.APP)
        intentId?.let { controller.awaitCleanup(it) }
        linkOwner.disconnectNow(disconnectClaim)
    }
    suspend fun lendLink(address: String, prefer: LinkPreference) = linkOwner.lendLink(address, prefer)
    fun refreshManifest() = linkOwner.refreshManifest()
    fun isConnectedTo(address: String) = linkOwner.isConnectedTo(address)
    suspend fun setAutoSync(address: String, enabled: Boolean) = linkOwner.setAutoSync(address, enabled)
    suspend fun setCompanionAssociation(address: String, associationId: Int?) = linkOwner.setCompanionAssociation(address, associationId)
    fun setTimeFromPhone() = linkOwner.setTimeFromPhone()
    fun flush() = linkOwner.flush()
    fun requestStatus() = linkOwner.requestStatus()
    fun refreshConfig() = linkOwner.refreshConfig()
    fun applyConfig(patch: ConfigPatch, change: DeviceMessage.ConfigChange = DeviceMessage.ConfigChange.SETTINGS) = linkOwner.applyConfig(patch, change)
    suspend fun applySensorConfig(patch: ConfigPatch) = linkOwner.applySensorConfig(patch)
    suspend fun readLocation() = linkOwner.readLocation()
    suspend fun writeLocation(patch: ConfigPatch) = linkOwner.writeLocation(patch)
    fun startWifiScan() = linkOwner.startWifiScan()
    fun clearWifiScan() = linkOwner.clearWifiScan()
    fun rebootAndReconnect() = linkOwner.rebootAndReconnect()
    fun refreshLogTail(maxBytes: Int = DeviceSession.LOG_RING_BYTES) = linkOwner.refreshLogTail(maxBytes)
    fun fetchToken() = linkOwner.fetchToken()
    fun rotateToken() = linkOwner.rotateToken()
    fun clearBonds() = linkOwner.clearBonds()
    suspend fun touchSeen(address: String, name: String?) = linkOwner.touchSeen(address, name)
    suspend fun forget(address: String) { if (this.address == address) disconnectNow(); linkOwner.forget(address) }

    private companion object {
        val nextIntent = AtomicLong(0)

        /** Endings with no action for the user, so a failed listing says the list was not ready. */
        val GENERIC_ENDS = setOf(SyncEndReason.LINK_LOST, SyncEndReason.OTHER)
    }
}

/** A Stop the person or the app chose is [SyncEndReason.STOPPED]; one the OS forced is [SyncEndReason.INTERRUPTED]. */
internal fun StopSource.endReason(): SyncEndReason = when (this) {
    StopSource.USER, StopSource.APP -> SyncEndReason.STOPPED
    StopSource.SYSTEM, StopSource.FGS_TIMEOUT -> SyncEndReason.INTERRUPTED
}

/** Per-device collection holder, see [DeviceFacade.ownership]. */
enum class DeviceOwnership { IDLE, INTERACTIVE, BACKGROUND }

internal fun deviceOwnership(collectionActive: Boolean, backgroundOwner: String?): DeviceOwnership = when {
    backgroundOwner != null -> DeviceOwnership.BACKGROUND
    collectionActive -> DeviceOwnership.INTERACTIVE
    else -> DeviceOwnership.IDLE
}
