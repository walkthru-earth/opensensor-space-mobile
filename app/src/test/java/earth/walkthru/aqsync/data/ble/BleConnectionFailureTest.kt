package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionFailureTest {
    @Test
    fun `only stale or failed keys close the link with the pairing repair message`() {
        val missing = BleConnectionFailure.closedState(DisconnectCause.Auth(AuthKind.BOND_MISSING))
        assertEquals(ConnectionState.Failed(BleConnectionFailure.PAIRING_REPAIR_MESSAGE, BleDisconnectCauses.KEY_MISSING), missing)
        val failedKeys = BleConnectionFailure.closedState(DisconnectCause.Auth(AuthKind.ENCRYPTION_FAILED))
        assertTrue(BleConnectionFailure.needsPairingRepair(failedKeys))
        // A PIN prompt that timed out is an unfinished pairing, not a stale one.
        for (kind in listOf(AuthKind.BOND_FAILED, AuthKind.TOKEN_REJECTED)) {
            val state = BleConnectionFailure.closedState(DisconnectCause.Auth(kind))
            assertTrue("$kind", state is ConnectionState.Failed)
            assertFalse("$kind", BleConnectionFailure.needsPairingRepair(state))
        }
        assertEquals(ConnectionState.Disconnected, BleConnectionFailure.closedState(DisconnectCause.PeerClosed()))
        assertEquals(ConnectionState.Failed("LocalAbort", 133), BleConnectionFailure.closedState(DisconnectCause.LocalAbort(gattStatus = 133)))
    }

    @Test
    fun `a lost link asks for pairing again only for stale or failed keys`() {
        val repair = BleConnectionFailure.PAIRING_REPAIR_MESSAGE
        assertEquals(repair, BleConnectionFailure.authLossMessage(DisconnectCause.Auth(AuthKind.BOND_MISSING), "lost"))
        assertEquals(repair, BleConnectionFailure.authLossMessage(DisconnectCause.Auth(AuthKind.ENCRYPTION_FAILED), "lost"))
        assertEquals("lost", BleConnectionFailure.authLossMessage(DisconnectCause.Auth(AuthKind.BOND_FAILED), "lost"))
        assertEquals("lost", BleConnectionFailure.authLossMessage(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), "lost"))
    }

    @Test
    fun `auth disconnect during connect settle survives later teardown and stage failure`() {
        for (status in listOf(5, 15, 6, 61)) {
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
    fun `key statuses read as pairing repair and radio statuses do not`() {
        for (status in listOf(5, 15, 6, 61, BleDisconnectCauses.KEY_MISSING)) {
            assertEquals(BleConnectionFailure.PAIRING_REPAIR_MESSAGE, BleConnectionFailure.message(status))
        }
        for (status in listOf(8, 19, 22, 133)) {
            assertFalse(BleConnectionFailure.isRepairStatus(status))
        }
        assertFalse(BleConnectionFailure.isRepairStatus(null))
    }

    @Test
    fun `repair shortcut survives repositories retaining reason without status`() {
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed(BleConnectionFailure.PAIRING_REPAIR_MESSAGE)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("auth failure", 5)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("encryption failure", 15)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("key missing", 6)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("mic failure", 61)))
        assertTrue(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("stale bond", BleDisconnectCauses.KEY_MISSING)))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("stack error", 133)))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Failed("peer closed", 19)))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Bonding))
        assertFalse(BleConnectionFailure.needsPairingRepair(ConnectionState.Disconnected))
    }
}
