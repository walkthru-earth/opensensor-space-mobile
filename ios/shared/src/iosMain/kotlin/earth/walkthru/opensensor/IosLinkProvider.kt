package earth.walkthru.opensensor

import earth.walkthru.aqsync.collection.CollectionLink
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.LinkProvider
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.TimeoutPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS [LinkProvider] (#58, #81). Swift owns the radio and keeps one open [IosLink] per
 * connection. [connect] takes a sync lease of the identified link [linkFor] returns, waiting at
 * most [timeout] while a Debug, config or Files request holds it, and honours cancellation
 * throughout. A cancel or timeout that lands after the lease was taken gives it back, so nothing
 * stays lent.
 *
 * With no usable link, [dial] asks Swift on [main] to open one, then [linkFor] is polled until an
 * identified link appears or [timeout] passes. Without [dial] a missing link is unreachable.
 */
class IosLinkProvider internal constructor(
    private val linkFor: (String) -> IosLink?,
    private val timeout: Duration,
    private val main: CoroutineContext,
    private val dial: ((String, LinkPreference) -> Unit)? = null,
    private val poll: Duration = 100.milliseconds,
    /** Runs once the lease is taken, before it is lent. A test hook for a cancel landing there. */
    private val granted: suspend () -> Unit = {},
) : LinkProvider {
    constructor(linkFor: (String) -> IosLink?) : this(linkFor, 20.seconds, Dispatchers.Main)

    /** Swift reconnects through [dial] when a retry finds no open link for the device. */
    constructor(linkFor: (String) -> IosLink?, dial: (String, LinkPreference) -> Unit) :
        this(linkFor, 20.seconds, Dispatchers.Main, dial)

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
        withContext(main) { dialer(device, prefer) }
        return withTimeoutOrNull(timeout) {
            var found: IosLink? = null
            while (found == null) {
                delay(poll)
                found = usable(device, prefer)
            }
            found
        } ?: throw LinkClosedException(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))
    }

    private suspend fun usable(device: String, prefer: LinkPreference): IosLink? =
        withContext(main) { linkFor(device) }?.takeIf { it.isOpen && it.station != null && prefer.allows(it.kind) }

    private fun LinkPreference.allows(kind: TransportKind) = when (this) {
        LinkPreference.ANY -> true
        LinkPreference.LAN -> kind == TransportKind.LAN
        LinkPreference.BLE -> kind == TransportKind.BLE
    }
}
