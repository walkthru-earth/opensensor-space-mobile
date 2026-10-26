package space.opensensor.data.lan

import space.opensensor.data.ble.TransportKind
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision path behind "LAN token renewed": seen on the real phone after another client rotated
 * the device token — the LAN handshake got `ERROR` 14, the app fell back to Bluetooth and never
 * fetched a new token because a (dead) one was still stored.
 */
class LanTokenPolicyTest {
    private val addr = "AA:BB:CC:DD:EE:01"

    @Test
    fun onlyAuthFailedInvalidatesTheStoredToken() {
        val p = LanTokenPolicy()
        assertTrue(p.invalidatesToken(LanFailure.AUTH_FAILED))
        for (f in LanFailure.entries.filter { it != LanFailure.AUTH_FAILED }) {
            assertFalse(p.invalidatesToken(f), "$f must not drop the token")
        }
    }

    @Test
    fun aRejectedOrRotatedTokenCauseInvalidatesTheStoredToken() {
        val p = LanTokenPolicy()
        assertTrue(p.invalidatesToken(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED)))
        assertTrue(p.invalidatesToken(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED)))
        assertFalse(p.invalidatesToken(DisconnectCause.Auth(AuthKind.BOND_FAILED)))
        assertFalse(p.invalidatesToken(DisconnectCause.Busy))
        assertFalse(p.invalidatesToken(DisconnectCause.PeerClosed()))
    }

    @Test
    fun firstTimeFetchHappensOverBleForV2WhenNothingIsStored() {
        val p = LanTokenPolicy()
        assertTrue(p.shouldFetch(TransportKind.BLE, proto = 2, storedToken = null))
        assertTrue(p.shouldFetch(TransportKind.FAKE, proto = 2, storedToken = null))
        assertFalse(p.shouldFetch(TransportKind.LAN, proto = 2, storedToken = null), "never over LAN (ERROR 13 by contract)")
        assertFalse(p.shouldFetch(TransportKind.BLE, proto = 1, storedToken = null), "v1 devices have no GET_TOKEN")
        assertFalse(p.shouldFetch(TransportKind.BLE, proto = null, storedToken = null), "absent proto means v1")
        assertFalse(p.shouldFetch(TransportKind.BLE, proto = 2, storedToken = "ab".repeat(32)), "a stored token is not re-fetched")
    }

    @Test
    fun authFailedThenBleReadyReFetchesAndAnnouncesTheRenewalExactlyOnce() {
        val p = LanTokenPolicy()
        // LAN handshake rejected: repository clears the Room column and marks the address.
        assertTrue(p.invalidatesToken(LanFailure.AUTH_FAILED))
        p.markStale(addr)
        assertTrue(p.isStale(addr))
        // BLE session reaches Ready with the column now null -> fetch again.
        assertTrue(p.shouldFetch(TransportKind.BLE, proto = 2, storedToken = null))
        // Fetched and stored: this one is a renewal to announce…
        assertTrue(p.onFetched(addr))
        // …but only once; a later plain first-time fetch stays silent.
        assertFalse(p.onFetched(addr))
        assertFalse(p.isStale(addr))
    }

    @Test
    fun aFirstTimeFetchIsSilent() {
        val p = LanTokenPolicy()
        assertFalse(p.onFetched(addr))
    }

    @Test
    fun deliberateRotationOrForgetClearsTheMarkWithoutAnAnnouncement() {
        val p = LanTokenPolicy()
        p.markStale(addr)
        p.clear(addr)
        assertFalse(p.isStale(addr))
        assertFalse(p.onFetched(addr))
    }

    @Test
    fun marksArePerDevice() {
        val p = LanTokenPolicy()
        p.markStale(addr)
        assertFalse(p.isStale("AA:BB:CC:DD:EE:02"))
        assertFalse(p.onFetched("AA:BB:CC:DD:EE:02"))
        assertTrue(p.onFetched(addr))
    }
}
