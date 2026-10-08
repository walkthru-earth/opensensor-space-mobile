package earth.walkthru.aqsync.data.ble

import kotlinx.coroutines.CancellableContinuation
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Which `BluetoothGattCallback` method completes a GATT operation. */
enum class GattCallbackKind { SERVICES_DISCOVERED, MTU_CHANGED, CHARACTERISTIC_READ, CHARACTERISTIC_WRITE, DESCRIPTOR_WRITE }

/**
 * The one GATT operation in flight, with the callback it waits for.
 *
 * Android delivers callbacks on a binder thread, and a callback can arrive late, after its
 * operation timed out and the next one started. A callback therefore completes the pending
 * operation only when its kind and its characteristic match. For a descriptor write the
 * characteristic is the descriptor's owner, since every CCCD shares one UUID. Anything else is
 * dropped and reported through [onMismatch], so a late write callback can never complete the next
 * read with the wrong bytes. Link loss still fails whatever is pending, through [failAny].
 */
class PendingGattOperation<T>(private val onMismatch: (String) -> Unit = {}) {
    private class Pending<T>(val kind: GattCallbackKind, val characteristic: UUID?, val continuation: CancellableContinuation<T>)

    private val slot = AtomicReference<Pending<T>?>(null)

    /** Park [continuation] until the [kind] callback for [characteristic] (null = any) arrives. */
    fun arm(kind: GattCallbackKind, characteristic: UUID?, continuation: CancellableContinuation<T>) {
        slot.set(Pending(kind, characteristic, continuation))
    }

    /** Clear the slot if it still holds [continuation]; true when this call cleared it. */
    fun disarm(continuation: CancellableContinuation<T>): Boolean {
        val current = slot.get() ?: return false
        return current.continuation === continuation && slot.compareAndSet(current, null)
    }

    /** Resume the pending operation when it waits for this callback; false when nothing matched. */
    fun complete(kind: GattCallbackKind, characteristic: UUID?, value: T): Boolean {
        while (true) {
            val current = slot.get()
            if (current == null) {
                onMismatch("$kind callback for $characteristic with no operation pending; dropped")
                return false
            }
            val matches = current.kind == kind && (current.characteristic == null || current.characteristic == characteristic)
            if (!matches) {
                onMismatch("$kind callback for $characteristic while ${current.kind} for ${current.characteristic} is pending; dropped")
                return false
            }
            if (slot.compareAndSet(current, null)) {
                current.continuation.resume(value)
                return true
            }
        }
    }

    /** Fail whatever is pending with [value] (link lost, teardown). */
    fun failAny(value: T): Boolean {
        val current = slot.getAndSet(null) ?: return false
        current.continuation.resume(value)
        return true
    }
}
