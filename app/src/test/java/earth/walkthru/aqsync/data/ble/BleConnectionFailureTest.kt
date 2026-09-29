package earth.walkthru.aqsync.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionFailureTest {
    @Test
    fun `auth disconnect during connect settle survives later teardown and stage failure`() {
        for (status in listOf(5, 15)) {
            val failure = BleConnectionFailure()
            failure.recordDisconnected(status)
            failure.recordDisconnected(0)
            failure.recordDisconnected(19)
            val exception = try {
                failure.throwIfFailed()
                error("Discovery must not start on the disconnected link")
            } catch (e: TransportException) {
                e
            }
            assertEquals(status, exception.gattStatus)
            assertEquals(BleConnectionFailure.PAIRING_REPAIR_MESSAGE, exception.message)
        }
    }

    @Test
    fun `a clean disconnected callback cannot report successful connect`() {
        val failure = BleConnectionFailure()
        assertEquals(-1, failure.recordDisconnected(0))
        assertEquals(-1, failure.exceptionOrNull()!!.gattStatus)
        assertEquals(133, BleConnectionFailure.disconnectedResult(133))
    }

    @Test
    fun `a new connect attempt clears the prior failure`() {
        val failure = BleConnectionFailure()
        failure.recordDisconnected(5)
        failure.reset()
        assertNull(failure.exceptionOrNull())
        failure.throwIfFailed()
        assertEquals(133, failure.recordDisconnected(133))
    }

    @Test
    fun `repair shortcut survives repositories retaining reason without status`() {
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed(BleConnectionFailure.PAIRING_REPAIR_MESSAGE)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("auth failure", 5)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("encryption failure", 15)))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("stack error", 133)))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Bonding))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Disconnected))
    }
}
