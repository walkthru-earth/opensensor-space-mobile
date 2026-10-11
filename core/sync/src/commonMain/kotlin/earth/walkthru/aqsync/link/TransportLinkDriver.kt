package earth.walkthru.aqsync.link

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.FrameRecorder
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * Runs a [LinkSession] over an existing [AqTransport] until the Android and iOS drivers land
 * (#57, #58). It pumps `responseFrames` into the sink as they arrive, so the transport's own
 * queue never fills. The session must be the transport's only reader.
 *
 * [recorder] keeps the raw frames for Advanced, Debug. [info] stands in for the `info` read on a
 * transport that cannot read it. The iOS `IosLink` transport is one, and the native
 * side has already refused proto below 3 before lending it (`SyncWire.identity`).
 */
@OptIn(ExperimentalAtomicApi::class)
class TransportLinkDriver(
    private val transport: AqTransport,
    private val scope: CoroutineScope,
    private val recorder: FrameRecorder? = null,
    private val info: (suspend () -> DeviceInfo)? = null,
) : LinkDriver {
    override val kind: TransportKind get() = transport.kind

    private val sink = AtomicReference<OnceSink?>(null)
    private val pumps = Job(scope.coroutineContext[Job])

    override suspend fun open(sink: FrameSink): LinkInfo {
        val once = OnceSink(sink)
        this.sink.store(once)
        try {
            transport.connect()
        } catch (e: TransportException) {
            throw LinkClosedException(DisconnectCause.LocalAbort(gattStatus = e.gattStatus), e)
        }
        // A device that connects but gives no valid info is not verified, not unreachable.
        val info = try {
            info?.invoke() ?: DeviceInfo.parse(transport.readInfo().decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cause = e as? TransportException ?: TransportException("bad device info (${e.message})", cause = e)
            throw LinkClosedException(DisconnectCause.FramingViolation("bad device info"), cause)
        }
        val pumpScope = CoroutineScope(scope.coroutineContext + pumps)
        pumpScope.launch {
            for (raw in transport.responseFrames) {
                recorder?.rx(raw)
                once.onFrame(raw)
            }
            once.onClosed(DisconnectCause.PeerClosed())
        }
        pumpScope.launch { transport.statusNotifications.collect { once.onPush(PushChannel.STATUS, it) } }
        pumpScope.launch { transport.liveNotifications.collect { once.onPush(PushChannel.LIVE, it) } }
        pumpScope.launch {
            val end = transport.connectionState.first { it is ConnectionState.Failed || it is ConnectionState.Disconnected }
            once.onClosed(
                if (end is ConnectionState.Failed) {
                    DisconnectCause.LocalAbort(gattStatus = end.gattStatus)
                } else {
                    DisconnectCause.PeerClosed()
                },
            )
        }
        return LinkInfo(kind, payloadMax = transport.mtu - 3, info = info)
    }

    override suspend fun send(body: ByteArray, deadline: Duration) {
        recorder?.tx(body)
        withTimeoutOrNull(deadline) { transport.writeControl(body) }
            ?: throw LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.SEND, body[0].toInt() and 0xFF))
    }

    override fun abort(cause: DisconnectCause) {
        sink.load()?.onClosed(cause)
        pumps.cancel()
        scope.launch(NonCancellable) { runCatching { transport.disconnect() } }
    }
}
