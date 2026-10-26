package space.opensensor.data.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Paced characteristic reads of `live` and `status` on a Bluetooth link whose MTU cannot notify a
 * full snapshot (#159). The firmware caches every snapshot and omits a notification larger than
 * MTU − 3, so below [FULL_SNAPSHOT_MTU] the phone reads both values itself. Each read is a GATT
 * long read, which returns the latest complete value.
 *
 * The driver's operation queue keeps every read apart from other GATT operations. The poller adds
 * the transfer pause. A read starts only while `paused` is false, and [quiesce] waits for a read in
 * flight, so a transfer that raised its flag and then quiesced never shares the link with one. The
 * loop ends once the link is no longer ready, and a cancel ends it at the next suspension.
 */
class SnapshotPoller(private val interval: Duration = SNAPSHOT_READ_INTERVAL) {
    private val gate = Mutex()

    /** Reads `live` then `status` every [interval] until [transport] stops being ready. */
    suspend fun run(transport: DeviceTransport, paused: () -> Boolean, onLive: (ByteArray) -> Unit, onStatus: (ByteArray) -> Unit) {
        while (true) {
            delay(interval)
            gate.withLock {
                if (!transport.connectionState.value.isReady) return
                if (paused()) return@withLock
                read(transport::readLive, onLive)
                read(transport::readStatus, onStatus)
            }
        }
    }

    /** Returns once no read is in flight. A transfer calls it after raising its pause flag. */
    suspend fun quiesce() = gate.withLock { }

    private suspend fun read(read: suspend () -> ByteArray, deliver: (ByteArray) -> Unit) {
        val value = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed read retries at the next tick. A lost link ends the loop there.
            return
        }
        deliver(value)
    }

    companion object {
        /** Largest `live` or `status` JSON the firmware publishes. */
        const val SNAPSHOT_MAX_BYTES = 480

        /** The smallest ATT MTU whose notification carries the largest snapshot. */
        const val FULL_SNAPSHOT_MTU = SNAPSHOT_MAX_BYTES + 3

        /** The firmware stores a sample and publishes both snapshots every 10 s. */
        val SNAPSHOT_READ_INTERVAL: Duration = 10.seconds

        /** True when [kind] at [mtu] can omit a snapshot notification. LAN frames carry every snapshot. */
        fun needed(kind: TransportKind, mtu: Int): Boolean = kind == TransportKind.BLE && mtu < FULL_SNAPSHOT_MTU
    }
}
