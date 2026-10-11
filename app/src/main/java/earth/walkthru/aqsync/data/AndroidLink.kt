package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.FrameRecorder
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.Heartbeat
import earth.walkthru.aqsync.link.LinkDriver
import earth.walkthru.aqsync.link.LinkInfo
import earth.walkthru.aqsync.link.LinkLog
import earth.walkthru.aqsync.link.LinkSession
import earth.walkthru.aqsync.link.TransportLinkDriver
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One Android connection. The [LinkDriver] side carries every frame for the [LinkSession].
 * The [AqTransport] side is a read only view for identity, connection state, the GATT `status`
 * and `live` reads and the push flows, which [DeviceSession] still takes until #58 retires
 * [AqTransport] on both platforms. Its `connect`, `responseFrames` and `writeControl` are not the
 * frame path, the session owns that.
 *
 * One instance per physical connection, never reused after it closes. [frames] is filled by the
 * driver for Advanced, Debug.
 */
interface AndroidLink :
    LinkDriver,
    AqTransport {
    override val kind: TransportKind
    val frames: FrameRecorder
}

/**
 * An [AndroidLink] over an [AqTransport] that has no driver of its own, the debug fake and test
 * transports. The fake is reused across connections, so each connection gets a new wrapper.
 */
class TransportLink(private val transport: AqTransport, scope: CoroutineScope) :
    AndroidLink,
    AqTransport by transport {
    override val frames = FrameRecorder()
    private val driver = TransportLinkDriver(transport, scope, frames)
    override val kind: TransportKind get() = transport.kind

    /** The wrapped transport, for identity checks such as `isFake`. */
    val wrapped: AqTransport get() = transport

    override suspend fun open(sink: FrameSink): LinkInfo = driver.open(sink)
    override suspend fun send(body: ByteArray, deadline: Duration) = driver.send(body, deadline)
    override fun abort(cause: DisconnectCause) = driver.abort(cause)
}

/** How long a whole open may take. BLE pairing waits for a passkey typed on the phone. */
val LINK_OPEN_TIMEOUT: Duration = 180.seconds

/**
 * The one place a [DeviceSession] is built over an [AndroidLink]. LAN gets the idle ping and
 * receive deadline, BLE relies on the GATT supervision timeout. Budgets follow the kind.
 */
fun AndroidLink.openableSession(scope: CoroutineScope, log: LinkLog): DeviceSession {
    val session = LinkSession(
        driver = this,
        scope = scope,
        heartbeat = if (kind == TransportKind.LAN) Heartbeat.LAN else null,
        inboxBudget = LinkSession.defaultBudget(kind),
        openTimeout = LINK_OPEN_TIMEOUT,
        log = log,
    )
    return DeviceSession(session, this, frames)
}
