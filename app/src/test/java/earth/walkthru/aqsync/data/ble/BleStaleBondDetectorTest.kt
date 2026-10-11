package earth.walkthru.aqsync.data.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleStaleBondDetectorTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"

    @Test
    fun `second consecutive bonded close while opening is a stale bond`() {
        for (status in listOf(19, 22, 133, BleLinkDriver.GATT_DISCONNECTED)) {
            val detector = BleStaleBondDetector(enabled = true)
            assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = status))
            assertTrue(detector.onOpenFailed(a, bonded = true, linkStatus = status))
        }
    }

    @Test
    fun `counts are per address`() {
        val detector = BleStaleBondDetector(enabled = true)
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        assertFalse(detector.onOpenFailed(b, bonded = true, linkStatus = 19))
        assertTrue(detector.onOpenFailed(a, bonded = true, linkStatus = 133))
    }

    @Test
    fun `ready and bond changes clear the count`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.onOpenFailed(a, bonded = true, linkStatus = 19)
        detector.onReady(a)
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        detector.onBondStateChanged(a)
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        assertTrue(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
    }

    @Test
    fun `unbonded, absent and other failures do not count and break the run`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.onOpenFailed(a, bonded = true, linkStatus = 19)
        assertFalse(detector.onOpenFailed(a, bonded = false, linkStatus = 19))
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        // A link that never connected (connect timeout, 133 from an absent sensor) reports null.
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = null))
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        // A supervision timeout is the radio, not the key.
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = BleDisconnectCauses.CONNECTION_TIMEOUT))
        assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
        assertTrue(detector.onOpenFailed(a, bonded = true, linkStatus = 19))
    }

    @Test
    fun `off where Android reports missing keys itself`() {
        val detector = BleStaleBondDetector(enabled = false)
        repeat(5) { assertFalse(detector.onOpenFailed(a, bonded = true, linkStatus = 19)) }
    }
}
