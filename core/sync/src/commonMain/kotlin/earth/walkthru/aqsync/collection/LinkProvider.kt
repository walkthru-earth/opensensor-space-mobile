package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportKind
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Which link a collection would like. The provider decides what it can open. */
enum class LinkPreference { ANY, LAN, BLE }

/**
 * Opens or reuses a device's link for a collection. Android implements it over its connection
 * repository (#57) and iOS over `SensorLink` (#58).
 */
fun interface LinkProvider {
    /**
     * A ready link to [device]. Throws `LinkClosedException` with its cause when none can be
     * opened, so the controller can tell a retryable loss from a refused credential.
     *
     * A connect cancelled after the provider lent a link, but before it returned, must release
     * that lend itself. The controller never saw the [CollectionLink] and cannot hand it back.
     * The controller calls it only once the previous run of [device] let go of its link.
     *
     * It must honour cancellation or end within a bounded time, for example through a connect
     * timeout. A later run of [device] waits, holding its PROCESS lease, until the stopped run's
     * connect returns, so a connect that hangs keeps every later request in `Preparing(CONNECT)`.
     */
    suspend fun connect(device: String, prefer: LinkPreference): CollectionLink
}

/**
 * A ready link lent to one collection. [station] is the validated station id of the device, and
 * [maxRead] its advertised read window. [release] hands the link back once, after which the
 * provider may keep it open for the UI or close it. Stopping a collection never closes a link the
 * provider still uses.
 */
@OptIn(ExperimentalAtomicApi::class)
class CollectionLink(
    val session: DeviceSession,
    val station: String,
    val maxRead: Int,
    private val onRelease: () -> Unit = {},
) {
    val kind: TransportKind get() = session.transport.kind

    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
