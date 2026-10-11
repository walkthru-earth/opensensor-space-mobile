package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.TimeoutPhase
import earth.walkthru.aqsync.link.retryable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDisconnectCausesTest {
    @Test
    fun `GATT statuses map to causes`() {
        assertEquals(DisconnectCause.PeerClosed(), BleDisconnectCauses.ofStatus(BleDisconnectCauses.PEER_USER))
        assertEquals(DisconnectCause.PeerClosed(), BleDisconnectCauses.ofStatus(BleLinkDriver.GATT_DISCONNECTED))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_FAILED), BleDisconnectCauses.ofStatus(5))
        assertEquals(DisconnectCause.Auth(AuthKind.ENCRYPTION_FAILED), BleDisconnectCauses.ofStatus(15))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofStatus(BleDisconnectCauses.KEY_MISSING))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofStatus(6))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofStatus(61))
        assertEquals(DisconnectCause.LocalAbort(gattStatus = 22), BleDisconnectCauses.ofStatus(BleDisconnectCauses.LOCAL_HOST))
        assertEquals(DisconnectCause.LocalAbort(gattStatus = 8), BleDisconnectCauses.ofStatus(BleDisconnectCauses.CONNECTION_TIMEOUT))
        assertEquals(DisconnectCause.LocalAbort(gattStatus = 133), BleDisconnectCauses.ofStatus(133))
    }

    @Test
    fun `credential failures stop retries and radio failures do not`() {
        assertFalse(BleDisconnectCauses.ofStatus(BleDisconnectCauses.KEY_MISSING).retryable)
        assertFalse(BleDisconnectCauses.ofStatus(5).retryable)
        assertFalse(BleDisconnectCauses.ofStatus(BleDisconnectCauses.HCI_KEY_MISSING).retryable)
        assertFalse(BleDisconnectCauses.ofStatus(BleDisconnectCauses.HCI_MIC_FAILURE).retryable)
        assertTrue(BleDisconnectCauses.ofStatus(133).retryable)
        assertTrue(BleDisconnectCauses.ofStatus(BleDisconnectCauses.PEER_USER).retryable)
    }

    @Test
    fun `open failures find the bond reason in the cause chain`() {
        val missing = BleLinkDriver.BondException("lost its bond", missing = true)
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofOpenFailure(TransportException("connect", cause = missing)))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_FAILED), BleDisconnectCauses.ofOpenFailure(BleLinkDriver.BondException("pairing timed out")))
        assertEquals(DisconnectCause.LocalAbort(gattStatus = 133), BleDisconnectCauses.ofOpenFailure(TransportException("gatt", gattStatus = 133)))
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofOpenFailure(TransportException("stale bond", gattStatus = 61)))
        // The stale bond detector fails the open with the key missing marker.
        assertEquals(DisconnectCause.Auth(AuthKind.BOND_MISSING), BleDisconnectCauses.ofOpenFailure(TransportException(BleConnectionFailure.PAIRING_REPAIR_MESSAGE, BleDisconnectCauses.KEY_MISSING)))
        assertEquals(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0), BleDisconnectCauses.ofOpenFailure(BleLinkDriver.ConnectTimeoutException()))
        assertEquals(DisconnectCause.LocalAbort(), BleDisconnectCauses.ofOpenFailure(TransportException("no adapter")))
    }
}
