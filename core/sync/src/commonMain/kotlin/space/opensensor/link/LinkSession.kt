package space.opensensor.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.data.ble.ProtocolViolationException
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.FrameDecodeException
import space.opensensor.data.protocol.FrameType
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ProtoGate
import space.opensensor.data.protocol.Request
import space.opensensor.data.protocol.requireProto3
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Lifecycle of one physical connection. A closed session is never reopened, build a new one. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Opening : LinkState
    data class Ready(val info: LinkInfo) : LinkState
    data class Closed(val cause: DisconnectCause) : LinkState
}

/**
 * The link is gone, or refused this request. [disconnectCause] says why. A transport failure while
 * opening keeps its own message (a pairing timeout, a refused bond) and is the [cause].
 */
class LinkClosedException(val disconnectCause: DisconnectCause, cause: TransportException? = null) : TransportException(cause?.message ?: "link closed, ${disconnectCause.describe()}", cause?.gattStatus, cause)

/** A request made no progress in time. The link stays open and the reply is drained. */
class LinkTimeoutException(val timeout: DisconnectCause.Timeout) : TransportException("no reply, ${timeout.describe()}")

/**
 * Keepalive for links whose peer drops idle sockets. A `STATUS` ping goes out after
 * [pingAfterIdle] without RX, and the link closes after [deadline] without RX. Only bytes from
 * the reader count, never consumer progress.
 */
data class Heartbeat(val pingAfterIdle: Duration, val deadline: Duration) {
    init {
        require(pingAfterIdle < deadline) { "ping must come before the deadline" }
    }

    companion object {
        /** Ping well inside the firmware's 300 s LAN idle limit. */
        val LAN = Heartbeat(pingAfterIdle = 120.seconds, deadline = 180.seconds)
    }
}

/**
 * Sees the undelivered reply bytes after every change, from the reader's thread or the router's.
 * A driver uses it to stop reading at a high mark and resume below it (iOS `IosLink`). It must not
 * block or call back into the session.
 */
fun interface InboxWatch {
    fun onInboxLevel(bytes: Long)
}

/**
 * One session per physical connection. A single router coroutine owns every reply frame, so a
 * caller that goes away never leaves frames for the next request. Callers queue for the link in
 * FIFO order and each request owns it until its terminal frame (see [TerminalRules]).
 *
 * The driver's reader never waits on a consumer. Frames that are not yet consumed count against
 * [inboxBudget], and passing it closes the link with [DisconnectCause.ResponseOverflow]. A driver
 * that can stop reading watches the same count through [inboxWatch] and pauses well before that.
 * A cancelled or timed out request is drained to its terminal within its [DrainBound], with an
 * `ABORT` sent first on a proto 3 peer. A `CLOSE` the caller could not wait for is owed to the
 * link ([oweClose]) and goes out once that drain ends, before any later request.
 */
@OptIn(ExperimentalAtomicApi::class)
class LinkSession(
    private val driver: LinkDriver,
    scope: CoroutineScope,
    private val heartbeat: Heartbeat? = null,
    private val inboxBudget: Long = defaultBudget(driver.kind),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val openTimeout: Duration = 60.seconds,
    private val byeGrace: Duration = 5.seconds,
    private val log: LinkLog = LinkLog.NONE,
    private val inboxWatch: InboxWatch? = null,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val own = CoroutineScope(scope.coroutineContext + job)

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val queuedBytes = AtomicLong(0L)
    private val bye = AtomicReference<Frame.Bye?>(null)
    private val lastRx = AtomicReference(timeSource.markNow())
    private val writeLock = Mutex()
    private var router: Job? = null

    /**
     * The pipe this session really runs on, from its driver. Never a screen label, so a secret check
     * reads the link it would actually write to (#155).
     */
    val kind: TransportKind get() = driver.kind

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow<ByteArray?>(null)

    /** Latest `status` JSON, from a BLE notification or a LAN `STATUS` frame. */
    val status: StateFlow<ByteArray?> = _status.asStateFlow()

    private val _live = MutableStateFlow<ByteArray?>(null)

    /** Latest `live` JSON. */
    val live: StateFlow<ByteArray?> = _live.asStateFlow()

    private val _closed = CompletableDeferred<DisconnectCause>()

    /** Completes once with the first cause that ended the link. */
    val closed: Deferred<DisconnectCause> get() = _closed

    init {
        job.invokeOnCompletion { terminate(DisconnectCause.Deliberate("scope ended")) }
    }

    /**
     * Open the driver and apply the proto 3 gate. A device below proto 3 closes the session with
     * [DisconnectCause.UpdateFirmware] and this throws [LinkClosedException].
     */
    suspend fun open(): LinkInfo {
        check(_state.compareAndSet(LinkState.Idle, LinkState.Opening)) { "a LinkSession opens once" }
        // The router runs before the driver so a frame that arrives during open has a reader.
        router = own.launch { route() }
        val info = try {
            withTimeoutOrNull(openTimeout) { driver.open(OnceSink(sink)) }
        } catch (e: LinkClosedException) {
            terminate(e.disconnectCause)
            throw e
        } catch (e: CancellationException) {
            terminate(DisconnectCause.Deliberate("open cancelled"))
            throw e
        } catch (e: TransportException) {
            terminate(DisconnectCause.LocalAbort(gattStatus = e.gattStatus))
            throw LinkClosedException(_closed.getCompleted(), e)
        } catch (e: Throwable) {
            // For example a malformed info JSON. Never leave a connected link behind.
            terminate(DisconnectCause.FramingViolation("bad device info"))
            throw e
        }
        if (info == null) terminate(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))
        val gate = info?.let { requireProto3(it.info) }
        if (gate is ProtoGate.UpdateFirmware) terminate(DisconnectCause.UpdateFirmware(gate.reported, gate.required))
        if (info == null || !_state.compareAndSet(LinkState.Opening, LinkState.Ready(info))) {
            throw LinkClosedException(_closed.getCompleted())
        }
        lastRx.store(timeSource.markNow())
        log.event(LinkEvent.Opened(info.kind, info.proto, info.payloadMax))
        heartbeat?.let { hb -> own.launch { keepAlive(hb) } }
        return info
    }

    /**
     * Send [spec] and hand each reply frame to [onFrame], with `terminal` true on the last one.
     * Returns the first non-null result. Returning before the terminal frame, throwing,
     * cancelling or timing out all leave the rest of the reply to the router's drain.
     *
     * A `CLOSE` passes its handle as [oweCloseIfUnsent]. Abandoned before it was written, it is
     * then owed ([oweClose]). Once written, its reply is drained instead, so it never goes out twice.
     */
    suspend fun <R : Any> exchange(
        spec: RequestSpec,
        oweCloseIfUnsent: Int? = null,
        onFrame: suspend (frame: Frame, terminal: Boolean) -> R?,
    ): R {
        require(spec.expectsResponse) { "${Opcode.name(spec.op)} has no reply, use send" }
        return withSlot(spec, oweCloseIfUnsent) { owner -> receive(owner) { _, frame, terminal -> onFrame(frame, terminal) } }
    }

    /** [exchange] that also hands over each frame's bytes as received, for the debug raw command. */
    suspend fun <R : Any> exchangeRaw(
        spec: RequestSpec,
        onFrame: suspend (raw: ByteArray, frame: Frame, terminal: Boolean) -> R?,
    ): R {
        require(spec.expectsResponse) { "${Opcode.name(spec.op)} has no reply, use send" }
        return withSlot(spec) { owner -> receive(owner, onFrame) }
    }

    /** Send a request that has no reply frame, in turn with other requests. */
    suspend fun send(spec: RequestSpec) {
        require(!spec.expectsResponse) { "${Opcode.name(spec.op)} has a reply, use exchange" }
        withSlot(spec) { owner -> owner.complete = true }
    }

    /**
     * Write a `STATUS` request outside the request queue. Its answer is a push, which updates
     * [status] and the RX clock, so a ping never waits behind a long transfer.
     */
    suspend fun ping() {
        ensureUsable()
        writeLock.withLock { driver.send(Request.Status.encode(), RequestSpec.WRITE_DEADLINE) }
    }

    /**
     * Owe the device a `CLOSE` for [handle] without waiting for it. The router sends it once the
     * request in flight and any drain have ended, consumes its `CLOSED`, and only then admits the
     * next request. A cancelled transfer calls this instead of waiting, so Stop never queues behind
     * the abandoned `READ`. On a closed link this does nothing, the device closes handles on
     * disconnect.
     */
    fun oweClose(handle: Int) {
        if (_closed.isCompleted) return
        events.trySend(Event.OweClose(handle))
    }

    /** Close the link on purpose. [reason] is a fixed developer label. */
    fun close(reason: String) {
        terminate(DisconnectCause.Deliberate(reason))
    }

    // ---- reader side, any thread, never suspends ----

    private val sink = object : FrameSink {
        override fun onFrame(raw: ByteArray) {
            if (_closed.isCompleted) return
            markRx()
            if (raw.isEmpty()) {
                terminate(DisconnectCause.FramingViolation("empty frame"))
                return
            }
            when (raw[0].toInt() and 0xFF) {
                FrameType.STATUS -> _status.value = raw.copyOfRange(1, raw.size)
                FrameType.LIVE -> _live.value = raw.copyOfRange(1, raw.size)
                FrameType.BYE -> onBye(raw)
                else -> {
                    val queued = queue(raw.size.toLong())
                    if (queued > inboxBudget) {
                        terminate(DisconnectCause.ResponseOverflow(queued, inboxBudget))
                    } else {
                        events.trySend(Event.Rx(raw))
                    }
                }
            }
        }

        override fun onPush(channel: PushChannel, json: ByteArray) {
            if (_closed.isCompleted) return
            markRx()
            when (channel) {
                PushChannel.STATUS -> _status.value = json
                PushChannel.LIVE -> _live.value = json
            }
        }

        override fun onRxActivity() = markRx()

        override fun onClosed(cause: DisconnectCause) {
            val farewell = bye.load()
            val final = if (farewell != null && (cause is DisconnectCause.PeerClosed || cause == DisconnectCause.PeerReset)) {
                peerClosedCause(farewell)
            } else {
                cause
            }
            terminate(final, fromDriver = true)
        }
    }

    private fun markRx() = lastRx.store(timeSource.markNow())

    /** Moves the undelivered byte count and reports it to [inboxWatch]. */
    private fun queue(delta: Long): Long = queuedBytes.addAndFetch(delta).also { inboxWatch?.onInboxLevel(it) }

    private fun onBye(raw: ByteArray) {
        val frame = try {
            FrameCodec.decode(raw) as Frame.Bye
        } catch (_: FrameDecodeException) {
            terminate(DisconnectCause.FramingViolation("truncated BYE"))
            return
        }
        if (!bye.compareAndSet(null, frame)) return
        log.event(LinkEvent.ByeReceived(frame.reason))
        events.trySend(Event.ByeSeen)
        // The peer closes right after BYE. If it does not, stop waiting for it.
        own.launch {
            delay(byeGrace)
            terminate(peerClosedCause(frame))
        }
    }

    /**
     * A close from the driver lets the router forward the frames it already queued, so a reply
     * that arrived just before EOF (REBOOTING, then BYE, then close) still reaches its owner.
     */
    private fun terminate(cause: DisconnectCause, fromDriver: Boolean = false) {
        if (!_closed.complete(cause)) return
        _state.value = LinkState.Closed(cause)
        log.event(LinkEvent.Closed(cause))
        if (!fromDriver) driver.abort(cause)
        events.close()
        val pending = router
        if (fromDriver && pending != null && pending.isActive) {
            pending.invokeOnCompletion { job.cancel() }
        } else {
            job.cancel()
        }
    }

    private fun ensureUsable() {
        if (_closed.isCompleted) throw LinkClosedException(_closed.getCompleted())
        bye.load()?.let { throw LinkClosedException(peerClosedCause(it)) }
        check(_state.value is LinkState.Ready) { "LinkSession is not open" }
    }

    /** Undelivered reply bytes now. A watcher re-reads it under its own lock, since reports can land out of order. */
    val inboxBytes: Long get() = queuedBytes.load()

    /**
     * Waits until no request, drain (an `ABORT` and the abandoned reply) or owed `CLOSE` is left. For
     * native work that writes past the session, such as the iOS Bluetooth `GET_TOKEN`. Its caller
     * keeps new requests away meanwhile, so the wait is bounded by the drain deadlines.
     */
    suspend fun awaitQuiet() {
        ensureUsable()
        val done = CompletableDeferred<Unit>()
        if (events.trySend(Event.Quiet(done)).isFailure) throw LinkClosedException(_closed.getCompleted())
        select<Unit> {
            _closed.onAwait { throw LinkClosedException(it) }
            done.onAwait { }
        }
    }

    // ---- caller side ----

    private suspend fun <T> withSlot(spec: RequestSpec, oweCloseIfUnsent: Int? = null, block: suspend (Owner) -> T): T {
        ensureUsable()
        val owner = Owner(spec, oweCloseIfUnsent)
        if (events.trySend(Event.Enqueue(owner)).isFailure) throw LinkClosedException(_closed.getCompleted())
        try {
            select<Unit> {
                _closed.onAwait { throw LinkClosedException(it) }
                owner.admitted.onAwait { }
            }
            writeLock.withLock {
                owner.written = true
                driver.send(spec.body, spec.writeDeadline)
            }
            log.event(LinkEvent.Sent(spec.op))
            return block(owner)
        } finally {
            events.trySend(Event.Release(owner))
        }
    }

    private suspend fun <R : Any> receive(owner: Owner, onFrame: suspend (ByteArray, Frame, Boolean) -> R?): R {
        val spec = owner.spec
        var frames = 0
        while (true) {
            val phase = if (frames == 0) TimeoutPhase.FIRST_FRAME else TimeoutPhase.INTER_FRAME
            // The inbox comes first, so a frame forwarded before the close is never lost to it.
            val delivery = select<Delivery?> {
                owner.inbox.onReceive { it }
                _closed.onAwait { cause ->
                    router?.join()
                    owner.inbox.tryReceive().getOrNull() ?: throw LinkClosedException(cause)
                }
                onTimeoutOrNever(if (frames == 0) spec.firstFrameTimeout else spec.interFrameTimeout) { null }
            } ?: throw LinkTimeoutException(DisconnectCause.Timeout(phase, spec.op))
            queue(-delivery.raw.size.toLong())
            frames++
            val terminal = delivery.routing == Routing.TERMINAL
            if (terminal) owner.complete = true
            val result = onFrame(delivery.raw, delivery.frame, terminal)
            if (result != null) return result
            if (terminal) throw ProtocolViolationException("${Opcode.name(spec.op)} ended without a result")
        }
    }

    // ---- router, the only code that touches routing state ----

    private val waiting = ArrayDeque<Owner>()
    private var current: Owner? = null
    private var drain: Drain? = null
    private val owed = ArrayDeque<Int>()
    private val quiet = ArrayList<CompletableDeferred<Unit>>()

    private suspend fun route() {
        try {
            while (true) {
                val active = drain
                val left = active?.let { it.bound.maxTime - it.started.elapsedNow() }
                val event = select<Event?> {
                    events.onReceiveCatching { it.getOrNull() }
                    onTimeoutOrNever(left) { Event.DrainTimeout }
                } ?: return
                when (event) {
                    is Event.Rx -> onRx(event.raw)
                    is Event.Enqueue -> onEnqueue(event.owner)
                    is Event.Release -> onRelease(event.owner)
                    is Event.OweClose -> {
                        owed.addLast(event.handle)
                        admitNext()
                    }
                    // A late failure of an earlier drain must not end the current one.
                    is Event.OwedWriteFailed -> if (drain === event.drain) endDrain(event.drain)
                    Event.ByeSeen -> refuseWaiting()
                    is Event.AbortFailed -> if (drain === event.drain) {
                        event.drain.abortPending = false
                        finishDrainIfDone(event.drain)
                    }
                    Event.DrainTimeout -> drain?.let { exceed(it) }
                    is Event.Quiet -> {
                        quiet += event.done
                        admitNext()
                    }
                }
            }
        } catch (e: CancellationException) {
            terminate(DisconnectCause.Deliberate("scope ended"))
            throw e
        } catch (e: Throwable) {
            // A router bug must not leave callers waiting on a link nobody reads.
            terminate(DisconnectCause.LocalAbort())
            throw e
        }
    }

    private fun onRx(raw: ByteArray) {
        val size = raw.size.toLong()
        val type = raw[0].toInt() and 0xFF
        val frame = try {
            FrameCodec.decode(raw)
        } catch (_: FrameDecodeException) {
            queue(-size)
            if (type in KNOWN_TYPES) {
                terminate(DisconnectCause.FramingViolation("bad ${FrameType.name(type)} frame"))
            } else {
                log.event(LinkEvent.UnknownDropped(type))
            }
            return
        }
        drain?.let { d ->
            queue(-size)
            take(d, frame, size)
            return
        }
        val owner = current
        val routing = owner?.let { TerminalRules.classify(it.spec.op, frame) } ?: Routing.FOREIGN
        if (owner == null || routing == Routing.FOREIGN) {
            queue(-size)
            // ABORTED with op 0 while idle is the normal answer to a late ABORT.
            if (!(frame is Frame.Aborted && frame.op == 0)) log.event(LinkEvent.ForeignDropped(type, owner?.spec?.op ?: 0))
            return
        }
        owner.inbox.trySend(Delivery(frame, raw, routing))
        owner.forwarded++
        if (routing == Routing.TERMINAL) {
            owner.terminalForwarded = true
            log.event(LinkEvent.Completed(owner.spec.op, owner.forwarded))
            current = null
            admitNext()
        }
    }

    private fun onEnqueue(owner: Owner) {
        val farewell = bye.load()
        if (farewell != null) {
            owner.admitted.completeExceptionally(LinkClosedException(peerClosedCause(farewell)))
            return
        }
        waiting.addLast(owner)
        admitNext()
    }

    private fun onRelease(owner: Owner) {
        if (!owner.written) owner.oweCloseIfUnsent?.let { owed.addLast(it) }
        if (waiting.remove(owner)) {
            admitNext()
            return
        }
        discardInbox(owner)
        if (owner !== current) return
        current = null
        if (owner.written && owner.spec.expectsResponse && !owner.complete && !owner.terminalForwarded) {
            log.event(LinkEvent.Abandoned(owner.spec.op, owner.forwarded, written = true))
            startDrain(owner.spec, replied = owner.forwarded > 0)
        }
        admitNext()
    }

    private fun refuseWaiting() {
        val cause = peerClosedCause(bye.load())
        while (waiting.isNotEmpty()) waiting.removeFirst().admitted.completeExceptionally(LinkClosedException(cause))
    }

    private fun admitNext() {
        if (current != null || drain != null) return
        if (owed.isNotEmpty()) {
            sendOwedClose(owed.removeFirst())
            return
        }
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next.admitted.complete(Unit)) {
                current = next
                return
            }
        }
        quiet.forEach { it.complete(Unit) }
        quiet.clear()
    }

    private fun discardInbox(owner: Owner) {
        while (true) {
            val left = owner.inbox.tryReceive().getOrNull() ?: break
            queue(-left.raw.size.toLong())
        }
    }

    private fun startDrain(spec: RequestSpec, replied: Boolean) {
        val proto = (_state.value as? LinkState.Ready)?.info?.proto ?: 0
        val sendAbort = proto >= DeviceGatt.PROTOCOL_VERSION && spec.op != Opcode.ABORT
        val d = Drain(spec.op, spec.drainBound, timeSource.markNow(), abortPending = sendAbort, replied = replied)
        drain = d
        if (!sendAbort) return
        own.launch {
            try {
                writeLock.withLock { driver.send(Request.Abort.encode(), RequestSpec.WRITE_DEADLINE) }
                log.event(LinkEvent.AbortSent(spec.op))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                events.trySend(Event.AbortFailed(d))
            }
        }
    }

    /**
     * An owed `CLOSE` runs as a drain with no caller. Its `CLOSED`, or an `ERROR` for `CLOSE`,
     * ends it. A write that fails ends it at once, since the device then never saw the request and
     * closes the handle on the next `OPEN` or on disconnect.
     */
    private fun sendOwedClose(handle: Int) {
        val spec = RequestSpec.of(Request.Close(handle))
        val d = Drain(spec.op, spec.drainBound, timeSource.markNow(), abortPending = false, replied = false)
        drain = d
        own.launch {
            try {
                writeLock.withLock { driver.send(spec.body, spec.writeDeadline) }
                log.event(LinkEvent.CloseSent(handle))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                events.trySend(Event.OwedWriteFailed(d))
            }
        }
    }

    /**
     * A drain ends when the abandoned reply reached its terminal frame and, once `ABORT` went
     * out, its `ABORTED` arrived too. A stopped reply ends in `ABORTED` naming the op and
     * `ERROR` 16, in either order, and a late `ERROR` 16 is foreign to later requests (see
     * [TerminalRules.classify]). `ABORTED` with op 0 answers the `ABORT` only. It also ends the
     * reply when no frame of it came first, because the device runs commands in order, so an
     * unanswered write (lost, or cancelled while writing) never stalls the queue. A reply that
     * already reached its owner is never ended this way.
     */
    private fun take(d: Drain, frame: Frame, size: Long) {
        val nothingYet = d.frames == 0 && !d.replied
        d.frames++
        d.bytes += size
        if (frame is Frame.Aborted && d.abortPending) {
            d.abortPending = false
            if (frame.op == d.op || (frame.op == 0 && nothingYet)) d.terminalSeen = true
        } else if (TerminalRules.isAbortError(frame)) {
            if ((frame as Frame.Error).op == d.op || frame.op == 0) d.terminalSeen = true
        } else if (TerminalRules.classify(d.op, frame) == Routing.TERMINAL) {
            d.terminalSeen = true
        }
        if (finishDrainIfDone(d)) return
        if (d.frames > d.bound.maxFrames || d.bytes > d.bound.maxBytes) exceed(d)
    }

    private fun finishDrainIfDone(d: Drain): Boolean {
        if (!d.terminalSeen || d.abortPending) return false
        endDrain(d)
        return true
    }

    private fun endDrain(d: Drain) {
        log.event(LinkEvent.Drained(d.op, d.frames, d.bytes))
        drain = null
        admitNext()
    }

    private fun exceed(d: Drain) {
        terminate(DisconnectCause.DrainExceeded(d.op, d.frames, d.started.elapsedNow()))
    }

    // ---- heartbeat ----

    private suspend fun keepAlive(hb: Heartbeat) {
        var lastPing: ComparableTimeMark? = null
        while (true) {
            val rxIdle = lastRx.load().elapsedNow()
            if (rxIdle >= hb.deadline) {
                terminate(DisconnectCause.HeartbeatDeadline(rxIdle))
                return
            }
            if (rxIdle >= hb.pingAfterIdle && (lastPing == null || lastPing.elapsedNow() >= hb.pingAfterIdle)) {
                lastPing = timeSource.markNow()
                own.launch {
                    try {
                        ping()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed write surfaces as the driver's close, or as the deadline.
                    }
                }
            }
            val untilRxIdle = hb.pingAfterIdle - rxIdle
            val untilRepeat = lastPing?.let { hb.pingAfterIdle - it.elapsedNow() } ?: Duration.ZERO
            val untilPing = maxOf(untilRxIdle, untilRepeat)
            val untilDeadline = hb.deadline - rxIdle
            delay(minOf(untilPing, untilDeadline).coerceAtLeast(1.milliseconds))
        }
    }

    private class Owner(val spec: RequestSpec, val oweCloseIfUnsent: Int?) {
        val inbox = Channel<Delivery>(Channel.UNLIMITED)
        val admitted = CompletableDeferred<Unit>()

        // Written by the caller before it sends Release, read by the router after.
        var written = false
        var complete = false

        // Router only.
        var forwarded = 0
        var terminalForwarded = false
    }

    private class Delivery(val frame: Frame, val raw: ByteArray, val routing: Routing)

    private class Drain(
        val op: Int,
        val bound: DrainBound,
        val started: ComparableTimeMark,
        var abortPending: Boolean,
        val replied: Boolean,
    ) {
        var frames = 0
        var bytes = 0L
        var terminalSeen = false
    }

    private sealed interface Event {
        class Rx(val raw: ByteArray) : Event
        class Enqueue(val owner: Owner) : Event
        class Release(val owner: Owner) : Event
        class OweClose(val handle: Int) : Event
        class OwedWriteFailed(val drain: Drain) : Event
        data object ByeSeen : Event
        class AbortFailed(val drain: Drain) : Event
        data object DrainTimeout : Event
        class Quiet(val done: CompletableDeferred<Unit>) : Event
    }

    companion object {
        const val LAN_BUDGET: Long = 1L * 1024 * 1024
        const val BLE_BUDGET: Long = 256L * 1024

        fun defaultBudget(kind: TransportKind): Long = if (kind == TransportKind.BLE) BLE_BUDGET else LAN_BUDGET

        private val KNOWN_TYPES = setOf(
            FrameType.FILE, FrameType.LIST_END, FrameType.OPENED, FrameType.CHUNK, FrameType.READ_END,
            FrameType.CLOSED, FrameType.TIME_SET, FrameType.FLUSHED, FrameType.CONFIG, FrameType.WIFI_AP,
            FrameType.WIFI_SCAN_END, FrameType.REBOOTING, FrameType.LOG, FrameType.LOG_END, FrameType.TOKEN,
            FrameType.HELLO, FrameType.STATUS, FrameType.LIVE, FrameType.CONFIG_CHUNK, FrameType.BYE,
            FrameType.DIAG, FrameType.ABORTED, FrameType.DESCRIPTOR_CHUNK, FrameType.ERROR,
        )
    }
}
