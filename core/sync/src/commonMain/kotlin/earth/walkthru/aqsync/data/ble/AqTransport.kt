package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.link.UnavailableKind
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Link state as the UI sees it. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object DiscoveringServices : ConnectionState

    /** Waiting for the user to type the passkey shown on the device screen. */
    data object Bonding : ConnectionState
    data object NegotiatingMtu : ConnectionState
    data object EnablingNotifications : ConnectionState

    /** LAN: `"OSS1"` + token sent, waiting for `HELLO`. */
    data object Authenticating : ConnectionState
    data class Ready(val mtu: Int) : ConnectionState
    data class Failed(val reason: String, val gattStatus: Int? = null) : ConnectionState

    val isReady: Boolean get() = this is Ready
    val isBusy: Boolean
        get() = this is Connecting || this is DiscoveringServices || this is Bonding ||
            this is NegotiatingMtu || this is EnablingNotifications || this is Authenticating
}

open class TransportException(message: String, val gattStatus: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The transport cannot open until the user acts (radio off, permission missing, no adapter). Never retried. */
class TransportUnavailableException(val kind: UnavailableKind, message: String) : TransportException(message)

/** Which byte pipe a transport is. The protocol above it is identical. */
enum class TransportKind {
    BLE,
    LAN,
    FAKE,
    ;

    /**
     * Whether a secret (`wifi.psk`, `ble.pin`) may travel on this pipe. Only the Bluetooth link,
     * which is encrypted, and the in-process fake whose bytes never leave the phone. This checks the
     * pipe kind only, not the pairing mode. Pair mode `none` is encrypted without MITM protection and
     * is lab only per the firmware docs. The LAN socket is plaintext TCP and never carries one (#155).
     */
    val carriesSecrets: Boolean get() = this == BLE || this == FAKE
}

/**
 * The five GATT attributes of the OpenSensor Sync service, abstracted so the same protocol/sync code runs
 * over a real Bluetooth GATT link (`BleLinkDriver` in `app/`), a LAN TCP socket (`TcpLinkDriver` in
 * `app/`) or an in-process fake device.
 *
 * Byte-level only: framing and JSON live one layer up in [DeviceSession].
 */
interface AqTransport {
    /**
     * Stable identity of the peer: the Bluetooth address (or a fake id) — also over LAN, so a device
     * is the same known device (Room `KnownDevice` on Android) whichever pipe is active.
     */
    val address: String
    val displayName: String?

    val kind: TransportKind

    /** Human-readable endpoint: the Bluetooth address, or `host:port` for LAN. */
    val endpoint: String

    val connectionState: StateFlow<ConnectionState>

    /** Negotiated ATT MTU; `payload_max = mtu − 3`. Meaningful only when [ConnectionState.Ready]. */
    val mtu: Int

    /** Raw `status` notifications (UTF-8 JSON). Latest value wins. */
    val statusNotifications: Flow<ByteArray>

    /** Raw `live` notifications (UTF-8 JSON). Latest value wins. */
    val liveNotifications: Flow<ByteArray>

    /**
     * Raw `response` frames, in order, none dropped. A `CHUNK` lost here would silently corrupt a
     * file, so this is an unbounded channel rather than a conflated flow.
     */
    val responseFrames: ReceiveChannel<ByteArray>

    /** Connect, bond if needed, request MTU, discover, enable notifications. Idempotent. */
    suspend fun connect()

    suspend fun disconnect()

    suspend fun readInfo(): ByteArray
    suspend fun readStatus(): ByteArray
    suspend fun readLive(): ByteArray

    /** Write (with response) one request to `control`. ≤ 512 bytes. */
    suspend fun writeControl(request: ByteArray)

    /** Release everything; the instance cannot be reused afterwards. */
    fun close()
}
