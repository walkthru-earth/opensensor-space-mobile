package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.ProtocolViolationException
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.CollectionSummary
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.InsufficientSpaceException
import earth.walkthru.aqsync.data.sync.PlannedCollection
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkTimeoutException
import earth.walkthru.aqsync.link.describe
import earth.walkthru.aqsync.storage.StorageException
import earth.walkthru.aqsync.sync.SyncLock
import earth.walkthru.aqsync.sync.nowEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** What [CollectionController.start] did with a request. */
sealed interface StartResult {
    data class Started(val intentId: Long) : StartResult

    /** Another request of this device is still running. It goes on unchanged. */
    data class Busy(val activeIntentId: Long) : StartResult

    /** The OS refused the lease, and the state says so. */
    data class Refused(val kind: KeepAliveKind) : StartResult
}

/**
 * One device's collection from the tap to its outcome (#56). Preparation (connect, clock, `LIST`,
 * plan) is part of the collection, so it is kept alive and resumed like the transfer.
 *
 * Ownership. The controller's job is the only owner of the PROCESS lease, the WIFI lease, the
 * shared [permits] slot and the lent [CollectionLink]. All are let go in one outermost finally, in
 * reverse order of taking. PROCESS is taken inside [start], before the job exists, and held in
 * every non-terminal state, `Waiting` included. WIFI is held only while a LAN link carries
 * `LIST`, the plan, the transfer or the summary.
 *
 * Retry. A retryable loss waits per [retry] and starts again at `Preparing(CONNECT)`, planning
 * from a fresh `LIST`. A cached plan never decides what is missing. Partial files resume by
 * offset through the [ArchiveCollector].
 *
 * Stop. [stop] moves the state to `Stopped`, then cancels the job. Every transition takes one lock
 * and checks by identity that its run still owns the state, so Stop always wins and an old run
 * never writes over a newer one.
 *
 * One lender. A request started right after a Stop waits for the stopped job to let go of its
 * link and permit before it connects, so a provider never lends one link to two runs.
 *
 * [log] gets state names, causes and counts only, never names of networks, tokens or a PIN.
 */
@OptIn(ExperimentalAtomicApi::class)
class CollectionController(
    val device: String,
    private val links: LinkProvider,
    private val collector: ArchiveCollector,
    private val keepAlive: KeepAlive,
    private val scope: CoroutineScope,
    private val retry: RetryPolicy = RetryPolicy(),
    /** Shared by the controllers of every device to bound concurrent collections. */
    private val permits: Semaphore? = null,
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val clock: () -> Long = ::nowEpochMs,
    private val log: (String) -> Unit = {},
    /** Runs before Done with the link and leases held. Optional refresh faults belong to the adapter. */
    private val onFinalizing: suspend (CollectionLink, CollectionSummary) -> Unit = { _, _ -> },
    /** Delivers each fresh listing before planning, including when archive planning later fails. */
    private val onListed: (CollectionLink, List<Frame.File>, Frame.ListEnd) -> Unit = { _, _, _ -> },
    /** Delivers each settled file even if a later session failure prevents a terminal summary. */
    private val onOutcome: (CollectionIntent, FileOutcome) -> Unit = { _, _ -> },
    /** One terminal notification per request, independent of StateFlow conflation. Faults are isolated. */
    private val onTerminal: (CollectionState) -> Unit = {},
    /** Persists the actual clock acknowledgement against this exact link before any LIST. */
    private val onClockSet: suspend (CollectionLink, Frame.TimeSet) -> Unit = { _, _ -> },
    /** Awaits platform-owned link cleanup after return, while permit and PROCESS are still held. */
    private val onCleanup: suspend (CollectionIntent) -> Unit = {},
) {
    private val _state = MutableStateFlow<CollectionState>(CollectionState.Idle)

    /** The current request and, once it ended, how. */
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    private val _events = Channel<CollectionState>(EVENT_CAPACITY, BufferOverflow.DROP_OLDEST)

    /**
     * Terminal states, for one consumer that shows a toast. Old ones drop when nobody reads, and
     * the outcome stays in [state] either way.
     */
    val events: ReceiveChannel<CollectionState> get() = _events

    /** Guards every write of [_state] and [current]. Held only for a few reads and writes. */
    private val lock = SyncLock()

    /** The run started last, until its job ends. Guarded by [lock]. */
    private var current: Run? = null

    /**
     * Start [intent] unless a request is running. Takes the PROCESS lease before it returns, so a
     * tap holds it before any suspension point.
     */
    fun start(request: CollectionIntent): StartResult {
        require(request.device == device) { "intent for another device" }
        // A copy only this run holds. Ownership compares it by identity, so a caller that passes
        // the same intent again after a Stop never lets the old job write over the new run.
        val intent = request.copy()
        _state.value.let { if (it.active) return StartResult.Busy(it.intentId!!) }
        val process = keepAlive.acquire(KeepAliveKind.PROCESS, intent)?.let(::OnceLease)
        if (process == null) {
            val refused = CollectionState.Failed(intent.id, CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS))
            val published = lock.withLock {
                val free = !_state.value.active
                if (free) _state.value = refused
                free
            }
            if (!published) return busy()
            ended(refused)
            return StartResult.Refused(KeepAliveKind.PROCESS)
        }
        val run = Run(intent, process)
        val taken = lock.withLock {
            if (_state.value.active) return@withLock false
            // Stored before the state and the launch, so an observer that stops and starts again,
            // or a job that ends at once, already finds this run here.
            run.previous = current
            current = run
            _state.value = CollectionState.Preparing(intent, PrepareStep.CONNECT, 1)
            true
        }
        if (!taken) {
            process.close()
            return busy()
        }
        note("collection ${intent.id} start ${intent.origin.name.lowercase()} ${if (intent.scope is CollectionScope.One) "one" else "all"}")
        // ATOMIC runs the body even when Stop lands first, so its finally always lets go.
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(run) }
        val orphan = lock.withLock {
            run.job = job
            !run.ownedBy(_state.value)
        }
        if (orphan) job.cancel()
        return StartResult.Started(intent.id)
    }

    /**
     * End the running request, or only request [intentId] when given. False when nothing matched.
     * The state is `Stopped` when this returns. Leases go once the job reaches its finally.
     */
    fun stop(by: StopSource, intentId: Long? = null): Boolean = endActive(intentId) { CollectionState.Stopped(it, by) }

    /**
     * An acquired lease was subsequently refused by the OS, such as a foreground-service start
     * rejection. Only the named active request fails; a delayed refusal cannot cancel a restart.
     */
    fun keepAliveRefused(kind: KeepAliveKind, intentId: Long): Boolean =
        endActive(intentId) { CollectionState.Failed(it, CollectionFailure.KeepAliveRefused(kind)) }

    private fun endActive(intentId: Long?, terminalFor: (Long) -> CollectionState): Boolean {
        var terminal: CollectionState? = null
        val job = lock.withLock {
            val now = _state.value
            if (!now.active || (intentId != null && now.intentId != intentId)) return@withLock null
            terminal = terminalFor(now.intentId!!)
            _state.value = terminal!!
            current?.takeIf { it.ownedBy(now) }?.job
        }
        val endedState = terminal ?: return false
        job?.cancel()
        ended(endedState)
        return true
    }

    /**
     * Wait until request [intentId] has handed back its link, permit and leases. A terminal state
     * precedes cleanup, so an adapter must await this before releasing its own reservation or
     * process protection. Waiting is cancellable; cancellation cleanup may call it from a
     * `NonCancellable` context. A newer request is never waited for by accident.
     */
    suspend fun awaitCleanup(intentId: Long) {
        val done = lock.withLock {
            var run = current
            while (run != null && run.intent.id != intentId) run = run.previous
            run?.done
        }
        done?.join()
    }

    private fun busy(): StartResult = _state.value.let { s ->
        s.intentId?.takeIf { s.active }?.let { StartResult.Busy(it) } ?: StartResult.Busy(-1)
    }

    private suspend fun execute(run: Run) {
        val intent = run.intent
        val started = time.markNow()
        var permitted = false
        var attempt = 1
        var barren = 1
        try {
            // The stopped run before this one still holds its link and permit until its finally.
            // A cancelled wait ends only after it, so a chain of quick restarts keeps the order.
            withContext(NonCancellable) { run.previous?.done?.join() }
            lock.withLock { run.previous = null }
            // ATOMIC ran this body although a Stop or the scope may have ended it. Do not connect.
            currentCoroutineContext().ensureActive()
            permits?.let {
                it.acquire()
                permitted = true
            }
            while (true) {
                var progressed = false
                val end = try {
                    val summary = attemptOnce(run, attempt) { progressed = true }
                    advance(run, CollectionState.Finalizing(intent, FinalizeStep.SUMMARY), run.link?.kind)
                    currentCoroutineContext().ensureActive()
                    onFinalizing(checkNotNull(run.link), summary)
                    AttemptEnd.Finished(summary)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    classify(e)
                }
                when (end) {
                    is AttemptEnd.Finished -> return finish(run, CollectionState.Done(intent.id, end.summary))
                    is AttemptEnd.Fail -> return finish(run, CollectionState.Failed(intent.id, end.failure))
                    is AttemptEnd.Lost -> {
                        letGo("link") { run.link?.also { run.link = null }?.release() }
                        if (end.cause is DisconnectCause.Deliberate) return finish(run, CollectionState.Stopped(intent.id, StopSource.APP))
                        barren = if (progressed) 2 else barren + 1
                        val wait = retry.backoff(end.cause, barren, started.elapsedNow())
                        note("collection ${intent.id} lost in attempt $attempt, ${end.cause.describe()}")
                        if (wait == null) return finish(run, CollectionState.Failed(intent.id, CollectionFailure.Link(end.cause)))
                        attempt++
                        advance(run, CollectionState.Waiting(intent, end.cause, attempt, clock() + wait.inWholeMilliseconds, barren, retry.attempts), null)
                        delay(wait)
                    }
                }
            }
        } finally {
            // Cancelled by its scope, not by stop(). The request still needs an outcome. Each step
            // runs on its own, so an adapter that throws never keeps the permit or PROCESS held.
            letGo("outcome") { finish(run, CollectionState.Stopped(intent.id, StopSource.SYSTEM)) }
            letGo("wifi") { run.wifi?.also { run.wifi = null }?.close() }
            letGo("link") { run.link?.also { run.link = null }?.release() }
            withContext(NonCancellable) {
                try {
                    onCleanup(intent)
                } catch (e: Exception) {
                    note("collection cleanup adapter failed, ${e::class.simpleName}")
                }
            }
            if (permitted) letGo("permit") { permits?.release() }
            letGo("process") { run.process.close() }
            lock.withLock { if (current === run) current = null }
            run.done.complete()
        }
    }

    /** Connect, clock, a fresh `LIST`, plan and transfer. Throws what ended the attempt. */
    private suspend fun attemptOnce(run: Run, attempt: Int, progressed: () -> Unit): CollectionSummary {
        val intent = run.intent
        advance(run, CollectionState.Preparing(intent, PrepareStep.CONNECT, attempt), null)
        val link = run.link ?: links.connect(device, intent.linkPreference).also { run.link = it }
        advance(run, CollectionState.Preparing(intent, PrepareStep.CLOCK, attempt), link.kind)
        if (intent.clockConfirmed) setClock(intent, link)
        advance(run, CollectionState.Preparing(intent, PrepareStep.LIST, attempt), link.kind)
        val (files, end) = link.session.list()
        currentCoroutineContext().ensureActive()
        onListed(link, files, end)
        advance(run, CollectionState.Preparing(intent, PrepareStep.PLAN, attempt), link.kind)
        val onlyName = (intent.scope as? CollectionScope.One)?.name
        val planned = planFrom(link.station, files, onlyName)
        note("collection ${intent.id} planned ${planned.plan.missingCount} of ${planned.plan.entries.size} file(s)")
        val summary = collector.transfer(link.session, planned, link.station, device, link.maxRead, onOutcome = { outcome ->
            val previous = run.outcomes[outcome.name]
            // Repaired receipts may re-plan a committed file. Reuse must not erase this run's
            // earlier new success when adapters derive partial-run counts from these outcomes.
            val retainNew = previous is FileOutcome.Synced && !previous.alreadyArchived && outcome is FileOutcome.Synced && outcome.alreadyArchived
            if (!retainNew) run.outcomes[outcome.name] = outcome
            if (outcome is FileOutcome.Synced && !outcome.alreadyArchived) {
                run.newFiles++
                run.newBytes += outcome.bytes
            }
            onOutcome(intent, outcome)
        }) { p ->
            if (p.receivedBytes > 0) progressed()
            advance(run, CollectionState.Transferring(intent, p, link.kind), link.kind)
        }
        return summary.copy(newFiles = run.newFiles, newBytes = run.newBytes, outcomes = run.outcomes.values.toList())
    }

    /** The plan touches only this phone's archive, so a fault there is a storage fault. */
    private suspend fun planFrom(station: String, files: List<Frame.File>, onlyName: String?): PlannedCollection = try {
        collector.plan(station, files, onlyName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: InsufficientSpaceException) {
        throw e
    } catch (e: Exception) {
        throw StorageException("archive index unavailable", e)
    }

    /** Only an actual acknowledgement is recorded. A refused clock keeps the original files flowing. */
    private suspend fun setClock(intent: CollectionIntent, link: CollectionLink) {
        val accepted = try {
            link.session.synchronizeClockIfNeeded(clock)
        } catch (e: DeviceErrorException) {
            if (e is AuthFailedException || e is DeviceBusyException || e.frame.op == 0) throw e
            note("collection ${intent.id} clock not set, ERROR ${e.frame.code}")
            null
        }
        if (accepted != null) {
            // TIME_SET already happened. Persist only its receipt despite Stop, then normal
            // cancellation/ownership checks prevent LIST. No wire request inherits this context.
            withContext(NonCancellable) { onClockSet(link, accepted) }
        }
    }

    /** Publish [next] for [run], or end the job when a Stop already took the state. */
    private fun advance(run: Run, next: CollectionState, kind: TransportKind?) {
        if (!publish(run, next)) throw CancellationException("collection ${run.intent.id} no longer owns the state")
        syncLeases(run, next, kind)
        if (next !is CollectionState.Transferring) note("collection ${run.intent.id} ${label(next)}")
    }

    /** Publish a terminal state when [run] still owns it. A Stop that won keeps its own. */
    private fun finish(run: Run, terminal: CollectionState) {
        if (publish(run, terminal)) ended(terminal)
    }

    /** Write [next] when [run] owns the state. The check and the write hold [lock] together. */
    private fun publish(run: Run, next: CollectionState): Boolean = lock.withLock {
        run.ownedBy(_state.value).also { if (it) _state.value = next }
    }

    /** One cleanup step. A throw is logged by its class and the next step still runs. */
    private inline fun letGo(what: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            note("collection cleanup $what failed, ${e::class.simpleName}")
        }
    }

    /** [log] is the host's callback. A throw there never changes what the controller does. */
    private fun note(line: String) {
        try {
            log(line)
        } catch (_: Exception) {
            // Nothing to report it to.
        }
    }

    private fun ended(terminal: CollectionState) {
        try {
            onTerminal(terminal)
        } catch (_: Exception) {
            // A host notification cannot change the outcome or delay resource cleanup.
        }
        note("collection ${terminal.intentId} ${label(terminal)}")
        _events.trySend(terminal)
    }

    /** The one place WIFI is taken or let go. LAN only, and only where the link carries data. */
    private fun syncLeases(run: Run, state: CollectionState, kind: TransportKind?) {
        val wanted = kind == TransportKind.LAN && when (state) {
            is CollectionState.Preparing -> state.step == PrepareStep.LIST || state.step == PrepareStep.PLAN
            is CollectionState.Transferring, is CollectionState.Finalizing -> true
            else -> false
        }
        if (wanted && run.wifi == null && !run.wifiRefused) {
            // A refused Wi-Fi lock slows the link but does not break it, so the run goes on. It is
            // asked again only once the link stops carrying data, not on every progress update.
            run.wifi = keepAlive.acquire(KeepAliveKind.WIFI, run.intent)?.let(::OnceLease)
            run.wifiRefused = run.wifi == null
            if (run.wifiRefused) note("collection ${run.intent.id} wifi lease refused")
        } else if (!wanted) {
            run.wifi?.close()
            run.wifi = null
            run.wifiRefused = false
        }
    }

    private fun classify(e: Exception): AttemptEnd = when (e) {
        is LinkClosedException -> AttemptEnd.Lost(e.disconnectCause)
        is LinkTimeoutException -> AttemptEnd.Lost(e.timeout)
        is InsufficientSpaceException -> AttemptEnd.Fail(CollectionFailure.Storage(StorageFailure.NO_SPACE, (e.needed - e.available).coerceAtLeast(0)))
        is StorageException -> AttemptEnd.Fail(CollectionFailure.Storage(StorageFailure.IO))
        is AuthFailedException -> AttemptEnd.Lost(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED))
        is DeviceBusyException -> AttemptEnd.Lost(DisconnectCause.Busy)
        is DeviceErrorException -> AttemptEnd.Fail(CollectionFailure.Device(e.frame.code, e.frame.op))
        is ProtocolViolationException -> AttemptEnd.Lost(DisconnectCause.FramingViolation("reply broke the contract"))
        is TransportException -> AttemptEnd.Lost(DisconnectCause.LocalAbort(gattStatus = e.gattStatus))
        else -> AttemptEnd.Fail(CollectionFailure.Unexpected(e::class.simpleName ?: "unknown"))
    }

    private fun label(state: CollectionState): String = when (state) {
        CollectionState.Idle -> "idle"
        is CollectionState.Preparing -> "preparing ${state.step.name.lowercase()} attempt ${state.attempt}"
        is CollectionState.Transferring -> "transferring ${state.progress.filesDone} of ${state.progress.filesTotal}"
        is CollectionState.Finalizing -> "finalizing ${state.step.name.lowercase()}"
        is CollectionState.Waiting -> "waiting for attempt ${state.attempt}, ${state.lost.describe()}"
        is CollectionState.Done -> "done, ${state.summary.files} file(s), ${state.summary.notSaved.size} not saved"
        is CollectionState.Failed -> "failed, ${state.failure}"
        is CollectionState.Stopped -> "stopped by ${state.by.name.lowercase()}"
    }

    /**
     * One request's job and what it holds. [job] and [previous] are set under [lock] in [start],
     * and the rest is touched by the job only. [done] completes once the finally let go of all.
     */
    private class Run(val intent: CollectionIntent, val process: Lease) {
        val done: CompletableJob = Job()
        var job: Job? = null
        var previous: Run? = null
        var link: CollectionLink? = null
        var wifi: Lease? = null
        var wifiRefused = false
        var newFiles = 0
        var newBytes = 0L
        val outcomes = linkedMapOf<String, FileOutcome>()

        /** The intent is this run's own copy, so no other run's state ever matches it. */
        fun ownedBy(state: CollectionState): Boolean = state.active && state.intentOrNull() === intent
    }

    private sealed interface AttemptEnd {
        class Finished(val summary: CollectionSummary) : AttemptEnd
        class Fail(val failure: CollectionFailure) : AttemptEnd
        class Lost(val cause: DisconnectCause) : AttemptEnd
    }

    /** Makes any adapter's lease close once, whoever calls it. */
    private class OnceLease(private val delegate: Lease) : Lease {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) delegate.close()
        }
    }

    companion object {
        /** Terminal states kept for a slow toast consumer. */
        const val EVENT_CAPACITY = 8
    }
}

private fun CollectionState.intentOrNull(): CollectionIntent? = when (this) {
    is CollectionState.Preparing -> intent
    is CollectionState.Transferring -> intent
    is CollectionState.Finalizing -> intent
    is CollectionState.Waiting -> intent
    else -> null
}
