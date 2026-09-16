package earth.walkthru.aqsync.data.ble

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
    data class Ready(val mtu: Int) : ConnectionState
    data class Failed(val reason: String, val gattStatus: Int? = null) : ConnectionState

    val isReady: Boolean get() = this is Ready
    val isBusy: Boolean
        get() = this is Connecting || this is DiscoveringServices || this is Bonding ||
            this is NegotiatingMtu || this is EnablingNotifications
}

class TransportException(message: String, val gattStatus: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The five GATT attributes of the AQ Sync service, abstracted so the same protocol/sync code runs
 * over a real [BluetoothGatt][android.bluetooth.BluetoothGatt] link or an in-process fake device.
 *
 * Byte-level only: framing and JSON live one layer up in [DeviceSession].
 */
interface AqTransport {
    /** Stable identity of the peer (Bluetooth address, or a fake id). */
    val address: String
    val displayName: String?

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
