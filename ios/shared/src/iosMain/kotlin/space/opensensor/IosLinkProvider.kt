package space.opensensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLock
import space.opensensor.collection.CollectionLink
import space.opensensor.collection.LinkPreference
import space.opensensor.collection.LinkProvider
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.TimeoutPhase
import space.opensensor.link.UnavailableKind
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The iOS [LinkProvider] (#58, #81). Swift owns the radio and keeps one open [IosLink] per
 * connection. [connect] takes a sync lease of the identified link [linkFor] returns, waiting at
 * most [timeout] while a Debug, config or Files request holds it, and honours cancellation
 * throughout. A cancel or timeout that lands after the lease was taken gives it back, so nothing
 * stays lent.
 *
 * With no usable link, [dial] asks Swift on [main] to open one, then [linkFor] is polled until an
 * identified link appears or [timeout] passes. Without [dial] a missing link is unreachable.
 *
 * Swift reports each dial through its `done` callback, once, from any thread, as a [DialResult].
 * [DialResult.REFUSED] means the connection was refused or failed before it identified, and the
 * attempt fails at once as [DisconnectCause.Unreachable]. A radio that is off, a missing permission
 * or no adapter fails it as a [TransportUnavailableException] of that kind, which is not retried.
 * [DialResult.OPENED] means Swift has an identified link. Later calls are
 * ignored. While a dial of a device and preference is in flight, no attempt dials it again. It
 * stays in flight until `done`, until its link is found, or for at most [timeout], so a Swift side
 * that never answers still gets dialled again later.
 */
class IosLinkProvider internal constructor(
    private val linkFor: (String) -> IosLink?,
    private val timeout: Duration,
    private val main: CoroutineContext,
    private val dial: ((String, LinkPreference, (DialResult) -> Unit) -> Unit)? = null,
    private val poll: Duration = 100.milliseconds,
    /** Runs once the lease is taken, before it is lent. A test hook for a cancel landing there. */
    private val granted: suspend () -> Unit = {},
    /**
     * Swift's open link of the preferred kind, when Swift keeps more than one per device. Replaces
     * [linkFor], so a Wi-Fi link never hides an open Bluetooth one from a Bluetooth-only attempt.
     */
    private val pick: ((String, LinkPreference) -> IosLink?)? = null,
) : LinkProvider {
    constructor(linkFor: (String) -> IosLink?) : this(linkFor, 20.seconds, Dispatchers.Main)

    /**
     * Swift reconnects through [dial] when a retry finds no open link for the device, and calls its
     * `done` once with whether the link opened and identified, or why it could not.
     */
    constructor(linkFor: (String) -> IosLink?, dial: (String, LinkPreference, (DialResult) -> Unit) -> Unit) :
        this(linkFor, 20.seconds, Dispatchers.Main, dial)

    private val lock = NSLock()

    /** Dials Swift has not answered yet, by device and preference. Guarded by [lock]. */
    private val dialing = HashMap<Pair<String, LinkPreference>, Dial>()

    override suspend fun connect(device: String, prefer: LinkPreference): CollectionLink = lend(device, prefer).second

    /** The lent link and the [IosLink] under it. Nothing suspends after the lease is lent. */
    internal suspend fun lend(device: String, prefer: LinkPreference): Pair<IosLink, CollectionLink> {
        val link = ready(device, prefer)
        val station = link.station ?: throw LinkClosedException(DisconnectCause.Unreachable)
        var lease: IosLink.Lease? = null
        try {
            withTimeout(timeout) { lease = link.acquire(LeaseKind.SYNC) }
            granted()
            currentCoroutineContext().ensureActive()
        } catch (e: TimeoutCancellationException) {
            lease?.end()
            throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))
        } catch (e: LeaseRefusedException) {
            throw LinkClosedException(if (e.syncRunning) DisconnectCause.Busy else DisconnectCause.Unreachable)
        } catch (e: Throwable) {
            lease?.end()
            throw e
        }
        val held = checkNotNull(lease)
        return link to CollectionLink(link.session, station, link.maxRead, onRelease = held::end)
    }

    /** An open, identified link of the preferred kind, dialled when Swift has none. */
    private suspend fun ready(device: String, prefer: LinkPreference): IosLink {
        usable(device, prefer)?.let { return it }
        val dialer = dial ?: throw LinkClosedException(DisconnectCause.Unreachable)
        val key = device to prefer
        var mine = false
        val pending = locked {
            dialing[key]?.takeIf { it.started.elapsedNow() < timeout } ?: Dial().also {
                dialing[key] = it
                mine = true
            }
        }
        if (mine) {
            var asked = false
            try {
                withContext(main) {
                    asked = true
                    dialer(device, prefer) { answer -> settle(key, pending, answer) }
                }
            } catch (e: Throwable) {
                // A cancel after Swift was asked leaves its dial in flight, so a retry does not dial twice.
                if (!asked || e !is CancellationException) settle(key, pending, DialResult.REFUSED)
                throw e
            }
        }
        val found = withTimeoutOrNull(timeout) {
            var link = usable(device, prefer)
            while (link == null) {
                when {
                    // Wakes at once when Swift answers, else polls again.
                    !pending.done.isCompleted -> withTimeoutOrNull(poll) { pending.done.await() }
                    else -> when (val answer = pending.done.await()) {
                        DialResult.OPENED -> delay(poll)
                        DialResult.REFUSED -> throw LinkClosedException(DisconnectCause.Unreachable)
                        else -> throw TransportUnavailableException(checkNotNull(answer.unavailable), "dial ${answer.name.lowercase()}")
                    }
                }
                link = usable(device, prefer)
            }
            link
        } ?: throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))
        locked { if (dialing[key] === pending) dialing.remove(key) }
        return found
    }

    /** Swift's answer to one dial. The first answer counts, and the dial is no longer in flight. */
    private fun settle(key: Pair<String, LinkPreference>, dial: Dial, answer: DialResult) {
        dial.done.complete(answer)
        locked { if (dialing[key] === dial) dialing.remove(key) }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** One dial in flight. [done] completes with Swift's answer. */
    private class Dial {
        val started = TimeSource.Monotonic.markNow()
        val done = CompletableDeferred<DialResult>()
    }

    private suspend fun usable(device: String, prefer: LinkPreference): IosLink? =
        withContext(main) { pick?.invoke(device, prefer) ?: linkFor(device) }?.takeIf { it.isOpen && it.station != null && prefer.allows(it.kind) }

    private fun LinkPreference.allows(kind: TransportKind) = when (this) {
        LinkPreference.ANY -> true
        LinkPreference.LAN -> kind == TransportKind.LAN
        LinkPreference.BLE -> kind == TransportKind.BLE
    }
}

/** How Swift answers one dial. Only [OPENED] has a link, the others fail the attempt at once. */
enum class DialResult(internal val unavailable: UnavailableKind?) {
    /** The link is open and identified. */
    OPENED(null),

    /** Refused or failed before it identified, such as a sensor out of range. Retried later. */
    REFUSED(null),

    /** Bluetooth is off. Not retried until the user turns it on. */
    RADIO_OFF(UnavailableKind.RADIO_OFF),

    /** The app may not use Bluetooth. */
    PERMISSION_MISSING(UnavailableKind.PERMISSION_MISSING),

    /** This device has no Bluetooth the app can use. */
    NO_ADAPTER(UnavailableKind.NO_ADAPTER),
}
