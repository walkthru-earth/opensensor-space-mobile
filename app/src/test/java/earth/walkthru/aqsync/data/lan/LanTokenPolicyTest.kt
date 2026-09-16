package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.data.ble.TransportKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision path behind "LAN token renewed": seen on the real phone after another client rotated
 * the device token — the LAN handshake got `ERROR` 14, the app fell back to Bluetooth and never
 * fetched a new token because a (dead) one was still stored.
 */
class LanTokenPolicyTest {
    private val addr = "AA:BB:CC:DD:EE:01"

    @Test
    fun `only AUTH_FAILED invalidates the stored token`() {
        val p = LanTokenPolicy()
        assertTrue(p.invalidatesToken(LanFailure.AUTH_FAILED))
        for (f in LanFailure.entries.filter { it != LanFailure.AUTH_FAILED }) {
            assertFalse("$f must not drop the token", p.invalidatesToken(f))
        }
    }

    @Test
    fun `first-time fetch happens over BLE for v2 when nothing is stored`() {
        val p = LanTokenPolicy()
        assertTrue(p.shouldFetch(TransportKind.BLE, proto = 2, storedToken = null))
        assertTrue(p.shouldFetch(TransportKind.FAKE, proto = 2, storedToken = null))
        assertFalse("never over LAN (ERROR 13 by contract)", p.shouldFetch(TransportKind.LAN, proto = 2, storedToken = null))
        assertFalse("v1 devices have no GET_TOKEN", p.shouldFetch(TransportKind.BLE, proto = 1, storedToken = null))
        assertFalse("absent proto means v1", p.shouldFetch(TransportKind.BLE, proto = null, storedToken = null))
        assertFalse("a stored token is not re-fetched", p.shouldFetch(TransportKind.BLE, proto = 2, storedToken = "ab".repeat(32)))
    }

    @Test
    fun `AUTH_FAILED then BLE Ready re-fetches and announces the renewal exactly once`() {
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
    fun `a first-time fetch is silent`() {
        val p = LanTokenPolicy()
        assertFalse(p.onFetched(addr))
    }

    @Test
    fun `deliberate rotation or forget clears the mark without an announcement`() {
        val p = LanTokenPolicy()
        p.markStale(addr)
        p.clear(addr)
        assertFalse(p.isStale(addr))
        assertFalse(p.onFetched(addr))
    }

    @Test
    fun `marks are per device`() {
        val p = LanTokenPolicy()
        p.markStale(addr)
        assertFalse(p.isStale("AA:BB:CC:DD:EE:02"))
        assertFalse(p.onFetched("AA:BB:CC:DD:EE:02"))
        assertTrue(p.onFetched(addr))
    }
}
