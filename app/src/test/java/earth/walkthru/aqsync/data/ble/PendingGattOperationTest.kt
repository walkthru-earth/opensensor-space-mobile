package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.AqUuids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingGattOperationTest {
    @Test
    fun `late write callback cannot complete the next read`() = runTest {
        val dropped = mutableListOf<String>()
        val slot = PendingGattOperation<String> { dropped += it }
        val read = async {
            suspendCancellableCoroutine { cont ->
                slot.arm(GattCallbackKind.CHARACTERISTIC_READ, AqUuids.INFO, cont)
                cont.invokeOnCancellation { slot.disarm(cont) }
            }
        }
        runCurrent()

        assertFalse(slot.complete(GattCallbackKind.CHARACTERISTIC_WRITE, AqUuids.CONTROL, "late write"))
        assertFalse(slot.complete(GattCallbackKind.CHARACTERISTIC_READ, AqUuids.STATUS, "other characteristic"))
        runCurrent()
        assertFalse(read.isCompleted)
        assertEquals(2, dropped.size)

        assertTrue(slot.complete(GattCallbackKind.CHARACTERISTIC_READ, AqUuids.INFO, "info"))
        assertEquals("info", read.await())
        assertFalse("a duplicate callback finds nothing pending", slot.complete(GattCallbackKind.CHARACTERISTIC_READ, AqUuids.INFO, "again"))
    }

    @Test
    fun `descriptor writes match on the owning characteristic`() = runTest {
        val slot = PendingGattOperation<Int>()
        val cccd = async {
            suspendCancellableCoroutine { cont -> slot.arm(GattCallbackKind.DESCRIPTOR_WRITE, AqUuids.RESPONSE, cont) }
        }
        runCurrent()
        assertFalse(slot.complete(GattCallbackKind.DESCRIPTOR_WRITE, AqUuids.LIVE, 1))
        assertTrue(slot.complete(GattCallbackKind.DESCRIPTOR_WRITE, AqUuids.RESPONSE, 0))
        assertEquals(0, cccd.await())
    }

    @Test
    fun `link loss fails whatever is pending`() = runTest {
        val slot = PendingGattOperation<Int>()
        val mtu = async { suspendCancellableCoroutine { cont -> slot.arm(GattCallbackKind.MTU_CHANGED, null, cont) } }
        runCurrent()
        assertTrue(slot.failAny(-1))
        assertEquals(-1, mtu.await())
        assertFalse(slot.failAny(-1))
    }

    @Test
    fun `cancelled operation is disarmed so a late callback is dropped`() = runTest {
        val slot = PendingGattOperation<Int>()
        val op = async {
            suspendCancellableCoroutine { cont ->
                slot.arm(GattCallbackKind.SERVICES_DISCOVERED, null, cont)
                cont.invokeOnCancellation { slot.disarm(cont) }
            }
        }
        runCurrent()
        op.cancel()
        runCurrent()
        assertFalse(slot.complete(GattCallbackKind.SERVICES_DISCOVERED, null, 0))
    }
}
