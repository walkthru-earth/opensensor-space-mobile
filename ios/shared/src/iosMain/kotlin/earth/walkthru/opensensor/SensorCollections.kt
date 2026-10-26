package earth.walkthru.opensensor

import earth.walkthru.aqsync.collection.CollectionController
import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.CollectionLink
import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.CollectionScope
import earth.walkthru.aqsync.collection.CollectionState
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.LinkProvider
import earth.walkthru.aqsync.collection.RetryPolicy
import earth.walkthru.aqsync.collection.StartResult
import earth.walkthru.aqsync.collection.StopSource
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.collection.endReason
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.CollectionSummary
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.FileProblem
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.companion_lan_paused
import earth.walkthru.opensensor.strings.resources.companion_lan_sync_paused
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * One shared [CollectionController] per device on iOS (#81). The controller owns retries, plans
 * every attempt from a fresh `LIST` and holds the protection leases from [keepAlive]. Swift keeps
 * the radio, and the [IosLinkProvider] lends its open links or asks Swift to dial a new one.
 *
 * [collect] keeps the [SensorSync] callback shape. `progress` arrives on the main thread at most
 * four times a second, `finished` exactly once, last, after the link and leases were handed back.
 * A run that ends early still reports the files it saved and the ones that failed.
 *
 * A station syncs over one link at a time, shared with [SensorSync]. The clock receipt of an
 * acknowledged `SET_TIME` is recorded against the exact link it was sent over.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
class SensorCollections internal constructor(
    private val sync: SensorSync,
    private val provider: IosLinkProvider,
    private val recordClockAnchor: suspend (String, String, Long, Long) -> Unit,
    work: CoroutineContext,
    private val callbacks: CoroutineContext,
    private val keepAlive: KeepAlive = KeepAlive.None,
    private val retry: RetryPolicy = RetryPolicy(),
    private val progressInterval: Long = SensorSync.PROGRESS_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + work)

    /** Bounds concurrent collections across devices, as on Android. */
    private val permits = Semaphore(MAX_CONCURRENT)
    private val lock = NSLock()
    private val ids = AtomicLong(0)

    /** Guarded by [lock]. */
    private val controllers = HashMap<String, CollectionController>()

    /** Runs the OS interrupted, by device, until a controller accepts one again. Guarded by [lock]. */
    private val interrupted = LinkedHashMap<String, InterruptedRun>()

    /** How Swift starts an interrupted run, set while the app is on screen. Guarded by [lock]. */
    private var resumer: ((InterruptedRun) -> ResumeOutcome)? = null

    /** Requests by intent id, until their `finished` ran. Guarded by [lock]. */
    private val requests = HashMap<Long, Request>()

    /** The [IosLink] under each lent link, by identity, until it is released. Guarded by [lock]. */
    private val lent = HashMap<CollectionLink, IosLink>()

    /**
     * Collects every file of [device] this phone lacks, or only [onlyName]. [prefer] limits every
     * attempt to one transport. [origin] says who started it. Call on the main thread.
     */
    fun collect(
        device: String,
        onlyName: String?,
        prefer: LinkPreference,
        origin: RunOrigin,
        progress: (SyncProgress) -> Unit,
        finished: (SyncResult) -> Unit,
    ): SyncHandle {
        val id = ids.incrementAndFetch()
        val request = Request(device, onlyName, prefer, origin, progress, finished)
        val controller = controllerFor(device)
        // Registered before start, so a refusal published inside start still finds it.
        locked { requests[id] = request }
        val intent = CollectionIntent(
            id = id,
            device = device,
            scope = onlyName?.let { CollectionScope.One(it) } ?: CollectionScope.All,
            origin = if (origin == RunOrigin.AUTO_SYNC) CollectionOrigin.WORKER else CollectionOrigin.USER_TAP,
            clockConfirmed = true,
            linkPreference = prefer,
        )
        IosSyncDiagnostics.record(device, "sync", "${prefer.name.lowercase()} ${if (onlyName == null) "all files" else "one file"}")
        if (controller.start(intent) is StartResult.Busy) {
            locked { requests.remove(id) }
            scope.launch(callbacks) { finished(SyncResult(SyncEnd.IN_USE, 0, 0, 0)) }
            return SyncHandle(null)
        }
        // The controller took it, so it replaces the run the OS interrupted, once. A busy refusal
        // above leaves that run waiting.
        locked { interrupted.remove(device) }
        // ATOMIC runs the body even if the scope ends first, so `finished` fires on every path.
        scope.launch(start = CoroutineStart.ATOMIC) { deliver(controller, id, request) }
        return SyncHandle { controller.stop(StopSource.USER, id) }
    }

    /**
     * Ends the running collection of [device], if any, in any letter case. The app closed its link on
     * purpose. It ends the run in every phase, also while it waits to retry or a dial is in flight,
     * so no later dial or transfer happens. Other devices keep their runs.
     */
    fun stop(device: String) {
        locked {
            // A deliberate stop also ends a run the OS interrupted, so it never resumes.
            interrupted.keys.removeAll { it.equals(device, ignoreCase = true) }
            controllers.entries.filter { it.key.equals(device, ignoreCase = true) }.map { it.value }
        }.forEach { it.stop(StopSource.APP) }
    }

    /**
     * The Stop button for [device], in any letter case. It ends only a run the user started, in every
     * phase, also while the link is gone and the run waits to retry or a dial is in flight. Collect
     * all and Auto-sync runs keep their own cancel. Call on the main thread.
     */
    fun stopUserRun(device: String) {
        locked {
            requests.entries
                .filter { (_, request) -> request.origin == RunOrigin.USER && request.device.equals(device, ignoreCase = true) }
                .mapNotNull { (id, request) -> controllers[request.device]?.let { it to id } }
        }.forEach { (controller, id) -> controller.stop(StopSource.USER, id) }
    }

    /**
     * The OS ended the background time of [device]'s run, a user run or a Collect all step. The run ends with
     * [earth.walkthru.aqsync.collection.SyncEndReason.INTERRUPTED], keeps its saved files and
     * partials, and waits until [resumeInterrupted] gets it accepted. False when no such run is active.
     */
    fun interrupt(device: String): Boolean {
        val (controller, id) = locked {
            val controller = controllers[device] ?: return false
            val id = controller.state.value.intentId ?: return false
            val request = requests[id]?.takeIf { it.origin != RunOrigin.AUTO_SYNC } ?: return false
            interrupted[device] = InterruptedRun(device, request.onlyName, request.prefer)
            controller to id
        }
        if (controller.keepAliveRefused(KeepAliveKind.PROCESS, id)) return true
        locked { interrupted.remove(device) }
        return false
    }

    /**
     * Starts the interrupted runs again, oldest first, through [start]. Swift calls it when the scene
     * is active, and [start] begins one through its own entry point, so the screens follow the new
     * run. A run leaves the queue once its controller accepts it, or when [start] answers
     * [ResumeOutcome.SKIPPED]. One whose last run still cleans up, or one a transport refused for
     * now, waits and is tried again after any run's `finished` and on [retryInterrupted]. Call on the
     * main thread.
     */
    fun resumeInterrupted(start: (InterruptedRun) -> ResumeOutcome) {
        locked { resumer = start }
        retryInterrupted()
    }

    /** A transport became ready, so the waiting interrupted runs are tried again. Call on the main thread. */
    fun retryInterrupted() {
        val (start, waiting) = locked {
            val start = resumer ?: return
            // A device whose run still holds its reservation is tried after that run's `finished`.
            start to interrupted.values.filter { run -> requests.values.none { it.device == run.device } }
        }
        for (run in waiting) {
            // A nested retry from a Swift callback may have started it already.
            if (locked { interrupted[run.device] !== run }) continue
            if (start(run) != ResumeOutcome.SKIPPED) continue
            locked { if (interrupted[run.device] === run) interrupted.remove(run.device) }
        }
    }

    /** The app left the screen. Interrupted runs wait for the next [resumeInterrupted]. */
    fun holdInterrupted() {
        locked { resumer = null }
    }

    /**
     * The title and body of the notice that a run paused off screen, in the app's language. Swift
     * reads them once at launch, so the expiry handler posts without waiting. [done] runs on the main thread.
     */
    fun pausedNotice(done: (String, String) -> Unit) {
        scope.launch {
            val title = getString(Res.string.companion_lan_paused)
            val body = getString(Res.string.companion_lan_sync_paused)
            withContext(callbacks) { done(title, body) }
        }
    }

    /** Forgets an interrupted run of [device], as when the user closed or forgot the sensor. */
    fun dropInterrupted(device: String) {
        locked { interrupted.remove(device) }
    }

    /**
     * True from [collect] until its `finished` ran, also while the controller waits to retry. Swift
     * asks before it closes a link or lets another run take the device. The device matches in any
     * letter case, as in [stop]. Call on the main thread.
     */
    fun isActive(device: String): Boolean = locked { requests.values.any { it.device.equals(device, ignoreCase = true) } }

    private suspend fun deliver(controller: CollectionController, id: Long, request: Request) {
        val ticks = Channel<SyncProgress>(Channel.CONFLATED)
        val reporter = scope.launch { for (update in ticks) withContext(callbacks) { request.progress(update) } }
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var last: TimeSource.Monotonic.ValueTimeMark? = null
            var lastDone = -1
            controller.state.collect { state ->
                if (state !is CollectionState.Transferring || state.intent.id != id) return@collect
                val p = state.progress
                val now = TimeSource.Monotonic.markNow()
                val due = last?.let { (now - it) >= progressInterval.milliseconds } ?: true
                if (due || p.filesDone != lastDone) {
                    last = now
                    lastDone = p.filesDone
                    ticks.trySend(SyncProgress(p.filesDone, p.filesTotal, p.fileBytes, p.receivedBytes, p.bytesPerSecond, p.bytesTotal))
                }
            }
        }
        val terminal = request.ended.await()
        withContext(NonCancellable) {
            controller.awaitCleanup(id)
            watcher.cancel()
            ticks.close()
            if (terminal is CollectionState.Stopped) reporter.cancel() else reporter.join()
        }
        val result = locked {
            requests.remove(id)
            resultOf(terminal, request)
        }
        withContext(NonCancellable + callbacks) {
            IosSyncDiagnostics.record(controller.device, "result", "${result.end.name.lowercase()} · ${result.saved}/${result.files} saved")
            request.finished(result)
            // Its transport is free now, so a run interrupted while this one cleaned up can start.
            retryInterrupted()
        }
    }

    private fun controllerFor(device: String): CollectionController = locked {
        controllers.getOrPut(device) {
            CollectionController(
                device = device,
                links = links,
                collector = sync.collector,
                keepAlive = keepAlive,
                scope = scope,
                retry = retry,
                permits = permits,
                log = { syncLog(it) },
                onListed = { _, files, _ -> listed(device, files) },
                onOutcome = { intent, outcome -> locked { requests[intent.id]?.outcomes?.record(outcome) } },
                onTerminal = { state -> state.intentId?.let { id -> locked { requests[id] } }?.ended?.complete(state) },
                onClockSet = ::clockSet,
            )
        }
    }

    /**
     * Each attempt lists afresh, so the newest listing counts. Only the running request of
     * [device] lists, and a stopped one is cancelled before it could.
     */
    private fun listed(device: String, files: List<Frame.File>) = locked {
        val request = controllers[device]?.state?.value?.intentId?.let { requests[it] } ?: return@locked
        request.listed = request.onlyName?.let { name -> files.count { it.name == name } } ?: files.size
    }

    /** Lends a link only once its station is free of another sync on this phone. */
    private val links = LinkProvider { device, prefer ->
        val (link, inner) = provider.lend(device, prefer)
        // The archive's shared coordinator admits one sync per station, as on Android. Its release
        // ends the IosLink lease before the station is free, so a sync it admits finds the link idle.
        val outer = sync.collector.coordinator.admit(inner) { released -> locked { lent.remove(released) } }
        locked { lent[outer] = link }
        outer
    }

    /** The anchor of an acknowledged `SET_TIME`, recorded for the link it was sent over. */
    private suspend fun clockSet(link: CollectionLink, set: Frame.TimeSet) {
        val ios = locked { lent[link] } ?: return
        ios.clockChecked = true
        val boot = ios.boot ?: return
        recordClockAnchor(link.station, boot, set.epochNanos, set.monotonicMicros)
    }

    private fun resultOf(terminal: CollectionState, request: Request): SyncResult = when (terminal) {
        is CollectionState.Done -> resultOf(terminal.summary)
        // The shared reason, so a stop by the OS reads INTERRUPTED and one by the user or app STOPPED.
        is CollectionState.Stopped -> partialResult(SyncEnd.STOPPED, request.listed, request.outcomes, terminal.endReason() ?: SyncEndReason.STOPPED)
        is CollectionState.Failed -> partialResult(terminal.failure.syncEnd(), request.listed, request.outcomes, terminal.failure.endReason())
        else -> partialResult(SyncEnd.FAILED, request.listed, request.outcomes)
    }

    private fun resultOf(summary: CollectionSummary): SyncResult {
        val end = when {
            summary.complete -> SyncEnd.COMPLETE
            summary.notSaved.any { it.problem == FileProblem.STORAGE } -> SyncEnd.STORAGE_FAILED
            else -> SyncEnd.FILES_NOT_SAVED
        }
        return SyncResult(end, summary.files, summary.newFiles, summary.notSaved.size, summary.notSaved)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** One request's callbacks and what it settled. [outcomes] and [listed] are guarded by [lock]. */
    private class Request(
        val device: String,
        val onlyName: String?,
        val prefer: LinkPreference,
        val origin: RunOrigin,
        val progress: (SyncProgress) -> Unit,
        val finished: (SyncResult) -> Unit,
    ) {
        val outcomes = linkedMapOf<String, FileOutcome>()
        var listed = 0
        val ended = CompletableDeferred<CollectionState>()
    }

    internal companion object {
        const val MAX_CONCURRENT = 2
    }
}

/** Who started a run. The Stop button ends only [USER] runs, Disconnect and Forget end every run. */
enum class RunOrigin {
    /** The Sync button, Smart Sync or a one-file sync from Files. */
    USER,

    /** One step of Collect all, which ends through its own cancel. */
    COLLECT_ALL,

    /** An unattended Auto-sync step. It is never remembered as interrupted. */
    AUTO_SYNC,
}

/** How Swift answered one try to start an interrupted run again. */
enum class ResumeOutcome {
    /** A transport began it. The queue drops it once its controller accepts it. */
    STARTED,

    /** No transport can take it yet, such as while another command holds the link. It waits. */
    WAITING,

    /** Policy says it does not resume, such as a page reopened over Bluetooth for another sensor. */
    SKIPPED,
}

/** A run the OS interrupted off screen. [onlyName] is set for a one-file sync from Files. */
class InterruptedRun internal constructor(val device: String, val onlyName: String?, val prefer: LinkPreference) {
    override fun toString(): String = "InterruptedRun(${prefer.name.lowercase()}, ${if (onlyName == null) "all files" else "one file"})"
}
