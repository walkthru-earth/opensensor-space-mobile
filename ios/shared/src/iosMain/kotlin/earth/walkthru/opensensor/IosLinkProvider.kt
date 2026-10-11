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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS [LinkProvider] (#58). Swift owns the radio and keeps one open [IosLink] per connection,
 * so [connect] never dials. It takes a sync lease of the link [linkFor] returns, waiting at most
 * [timeout] while a Debug, config or Files request holds it, and honours cancellation throughout.
 * A cancel or timeout that lands after the lease was taken gives it back, so nothing stays lent.
 */
class IosLinkProvider internal constructor(
    private val linkFor: (String) -> IosLink?,
    private val timeout: Duration,
    private val main: CoroutineContext,
) : LinkProvider {
    constructor(linkFor: (String) -> IosLink?) : this(linkFor, 20.seconds, Dispatchers.Main)

    override suspend fun connect(device: String, prefer: LinkPreference): CollectionLink {
        val link = withContext(main) { linkFor(device) }
            ?.takeIf { it.isOpen && prefer.allows(it.kind) }
            ?: throw LinkClosedException(DisconnectCause.Unreachable)
        val station = link.station ?: throw LinkClosedException(DisconnectCause.Unreachable)
        var lease: IosLink.Lease? = null
        try {
            withTimeout(timeout) { lease = link.acquire(LeaseKind.SYNC) }
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
        return CollectionLink(link.session, station, link.maxRead, onRelease = held::end)
    }

    private fun LinkPreference.allows(kind: TransportKind) = when (this) {
        LinkPreference.ANY -> true
        LinkPreference.LAN -> kind == TransportKind.LAN
        LinkPreference.BLE -> kind == TransportKind.BLE
    }
}
