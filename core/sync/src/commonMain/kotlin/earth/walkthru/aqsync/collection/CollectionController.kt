package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.ProtocolViolationException
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.CollectionSummary
import earth.walkthru.aqsync.data.sync.InsufficientSpaceException
import earth.walkthru.aqsync.data.sync.PlannedCollection
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkTimeoutException
import earth.walkthru.aqsync.link.describe
import earth.walkthru.aqsync.storage.StorageException
import earth.walkthru.aqsync.sync.nowEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
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
 * Stop. [stop] moves the state to `Stopped` with a compare and set, then cancels the job. Every
 * later transition of that request checks that it still owns the state, so Stop always wins.
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

    private val current = AtomicReference<Run?>(null)

    /**
     * Start [intent] unless a request is running. Takes the PROCESS lease before it returns, so a
     * tap holds it before any suspension point.
     */
    fun start(request: CollectionIntent): StartResult {
        require(request.device == device) { "intent for another device" }
        // A copy only this run holds. Ownership compares it by identity, so a caller that passes
        // the same intent again after a Stop never lets the old job write over the new run.
        val intent = request.copy()
        val before = _state.value
        if (before.active) return StartResult.Busy(before.intentId!!)
        val process = keepAlive.acquire(KeepAliveKind.PROCESS, intent)?.let(::OnceLease)
        if (process == null) {
            val refused = CollectionState.Failed(intent.id, CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS))
            if (!_state.compareAndSet(before, refused)) return busy()
            ended(refused)
            return StartResult.Refused(KeepAliveKind.PROCESS)
        }
        if (!_state.compareAndSet(before, CollectionState.Preparing(intent, PrepareStep.CONNECT, 1))) {
            process.close()
            return busy()
        }
        log("collection ${intent.id} start ${intent.origin.name.lowercase()} ${if (intent.scope is CollectionScope.One) "one" else "all"}")
        val run = Run(intent, process)
        // ATOMIC runs the body even when Stop lands first, so its finally always lets go.
        run.job = scope.launch(start = CoroutineStart.ATOMIC) { execute(run) }
        current.store(run)
        if (!owns(run)) run.job?.cancel()
        return StartResult.Started(intent.id)
    }

    /**
     * End the running request, or only request [intentId] when given. False when nothing matched.
     * The state is `Stopped` when this returns. Leases go once the job reaches its finally.
     */
    fun stop(by: StopSource, intentId: Long? = null): Boolean {
        while (true) {
            val now = _state.value
            if (!now.active || (intentId != null && now.intentId != intentId)) return false
            val stopped = CollectionState.Stopped(now.intentId!!, by)
            if (_state.compareAndSet(now, stopped)) {
                current.load()?.takeIf { it.intent.id == stopped.intentId }?.job?.cancel()
                ended(stopped)
                return true
            }
        }
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
            permits?.let {
                it.acquire()
                permitted = true
            }
            while (true) {
                var progressed = false
                val end = try {
                    val summary = attemptOnce(run, attempt) { progressed = true }
                    advance(run, CollectionState.Finalizing(intent, FinalizeStep.SUMMARY), run.link?.kind)
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
                        run.link?.release()
                        run.link = null
                        if (end.cause is DisconnectCause.Deliberate) return finish(run, CollectionState.Stopped(intent.id, StopSource.APP))
                        barren = if (progressed) 2 else barren + 1
                        val wait = retry.backoff(end.cause, barren, started.elapsedNow())
                        log("collection ${intent.id} lost in attempt $attempt, ${end.cause.describe()}")
                        if (wait == null) return finish(run, CollectionState.Failed(intent.id, CollectionFailure.Link(end.cause)))
                        attempt++
                        advance(run, CollectionState.Waiting(intent, end.cause, attempt, clock() + wait.inWholeMilliseconds), null)
                        delay(wait)
                    }
                }
            }
        } finally {
            // Cancelled by its scope, not by stop(). The request still needs an outcome.
            finish(run, CollectionState.Stopped(intent.id, StopSource.SYSTEM))
            run.wifi?.close()
            run.wifi = null
            run.link?.release()
            run.link = null
            if (permitted) permits?.release()
            run.process.close()
            current.compareAndSet(run, null)
        }
    }

    /** Connect, clock, a fresh `LIST`, plan and transfer. Throws what ended the attempt. */
    private suspend fun attemptOnce(run: Run, attempt: Int, progressed: () -> Unit): CollectionSummary {
        val intent = run.intent
        advance(run, CollectionState.Preparing(intent, PrepareStep.CONNECT, attempt), null)
        val link = run.link ?: links.connect(device, LinkPreference.ANY).also { run.link = it }
        advance(run, CollectionState.Preparing(intent, PrepareStep.CLOCK, attempt), link.kind)
        if (intent.clockConfirmed) setClock(intent, link.session)
        advance(run, CollectionState.Preparing(intent, PrepareStep.LIST, attempt), link.kind)
        val (files, _) = link.session.list()
        advance(run, CollectionState.Preparing(intent, PrepareStep.PLAN, attempt), link.kind)
        val onlyName = (intent.scope as? CollectionScope.One)?.name
        val planned = planFrom(link.station, files, onlyName)
        log("collection ${intent.id} planned ${planned.plan.missingCount} of ${planned.plan.entries.size} file(s)")
        return collector.transfer(link.session, planned, link.station, device, link.maxRead) { p ->
            if (p.receivedBytes > 0) progressed()
            advance(run, CollectionState.Transferring(intent, p, link.kind), link.kind)
        }
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

    /** The user allowed the phone time. A refused `SET_TIME` keeps the original files flowing. */
    private suspend fun setClock(intent: CollectionIntent, session: DeviceSession) {
        try {
            session.synchronizeClockIfNeeded(clock)
        } catch (e: DeviceErrorException) {
            if (e is AuthFailedException || e is DeviceBusyException) throw e
            log("collection ${intent.id} clock not set, ERROR ${e.frame.code}")
        }
    }

    /** Publish [next] for [run], or end the job when a Stop already took the state. */
    private fun advance(run: Run, next: CollectionState, kind: TransportKind?) {
        while (true) {
            val now = _state.value
            if (!run.ownedBy(now)) throw CancellationException("collection ${run.intent.id} no longer owns the state")
            if (_state.compareAndSet(now, next)) break
        }
        syncLeases(run, next, kind)
        if (next !is CollectionState.Transferring) log("collection ${run.intent.id} ${label(next)}")
    }

    /** Publish a terminal state when [run] still owns it. A Stop that won keeps its own. */
    private fun finish(run: Run, terminal: CollectionState) {
        while (true) {
            val now = _state.value
            if (!run.ownedBy(now)) return
            if (_state.compareAndSet(now, terminal)) break
        }
        ended(terminal)
    }

    private fun ended(terminal: CollectionState) {
        log("collection ${terminal.intentId} ${label(terminal)}")
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
            if (run.wifiRefused) log("collection ${run.intent.id} wifi lease refused")
        } else if (!wanted) {
            run.wifi?.close()
            run.wifi = null
            run.wifiRefused = false
        }
    }

    private fun classify(e: Exception): AttemptEnd = when (e) {
        is LinkClosedException -> AttemptEnd.Lost(e.disconnectCause)
        is LinkTimeoutException -> AttemptEnd.Lost(e.timeout)
        is InsufficientSpaceException -> AttemptEnd.Fail(CollectionFailure.Storage(StorageFailure.NO_SPACE))
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

    /** One request's job and what it holds. Touched by its own job only, after [start]. */
    private class Run(val intent: CollectionIntent, val process: Lease) {
        var job: Job? = null
        var link: CollectionLink? = null
        var wifi: Lease? = null
        var wifiRefused = false

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

    private fun owns(run: Run): Boolean = run.ownedBy(_state.value)

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
