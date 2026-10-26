package space.opensensor.data.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import space.opensensor.data.ble.BleStaleBondDetector.Verdict

class BleStaleBondDetectorTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"

    /** A close once the link had tried encryption, at the bond check or the CCCD writes. */
    private fun BleStaleBondDetector.encrypted(address: String, status: Int?, bonded: Boolean = true) =
        onOpenFailed(address, bonded, status, afterEncryption = true)

    /** A close during the settle, service discovery or the MTU request. */
    private fun BleStaleBondDetector.early(address: String, status: Int?, bonded: Boolean = true) =
        onOpenFailed(address, bonded, status, afterEncryption = false)

    @Test
    fun `second consecutive bonded close after encryption is a stale bond`() {
        for (status in listOf(19, 22)) {
            val detector = BleStaleBondDetector(enabled = true)
            assertEquals(Verdict.NONE, detector.encrypted(a, status))
            assertEquals(Verdict.STALE, detector.encrypted(a, status))
        }
    }

    @Test
    fun `the sensor refusing the old key twice while opening still trips`() {
        // CoreS3 firmware ends a link whose encryption failed with HCI 0x13 (status.md, 2026-10-11).
        val detector = BleStaleBondDetector(enabled = true)
        assertEquals(Verdict.NONE, detector.encrypted(a, BleDisconnectCauses.PEER_USER))
        assertEquals(Verdict.STALE, detector.encrypted(a, BleDisconnectCauses.PEER_USER))
    }

    @Test
    fun `two 133 or bare closes during discovery do not trip`() {
        for (status in listOf(BleLinkDriver.GATT_ERROR_133, BleLinkDriver.GATT_DISCONNECTED)) {
            val detector = BleStaleBondDetector(enabled = true)
            repeat(4) { assertEquals("status $status", Verdict.NONE, detector.early(a, status)) }
        }
    }

    @Test
    fun `133 or bare closes after encryption do not trip either`() {
        for (status in listOf(BleLinkDriver.GATT_ERROR_133, BleLinkDriver.GATT_DISCONNECTED)) {
            val detector = BleStaleBondDetector(enabled = true)
            repeat(4) { assertEquals("status $status", Verdict.NONE, detector.encrypted(a, status)) }
        }
    }

    @Test
    fun `peer or host closes before encryption only hint and keep retries`() {
        val detector = BleStaleBondDetector(enabled = true)
        assertEquals(Verdict.NONE, detector.early(a, 19))
        assertEquals(Verdict.HINT, detector.early(a, 22))
        // The hint repeats while the run lasts, it never becomes a hard verdict on its own.
        assertEquals(Verdict.HINT, detector.early(a, 19))
    }

    @Test
    fun `a hint becomes a stale bond once two closes followed encryption`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.early(a, 19)
        assertEquals(Verdict.HINT, detector.encrypted(a, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `counts are per address`() {
        val detector = BleStaleBondDetector(enabled = true)
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        assertEquals(Verdict.NONE, detector.encrypted(b, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 22))
    }

    @Test
    fun `ready and bond changes clear the count`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.encrypted(a, 19)
        detector.onReady(a)
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        detector.onBondStateChanged(a)
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `unbonded, absent and other failures do not count and break the run`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.encrypted(a, 19)
        assertEquals(Verdict.NONE, detector.encrypted(a, 19, bonded = false))
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        // A link that never connected (connect timeout, 133 from an absent sensor) reports null.
        assertEquals(Verdict.NONE, detector.encrypted(a, null))
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        // A supervision timeout is the radio, not the key.
        assertEquals(Verdict.NONE, detector.encrypted(a, BleDisconnectCauses.CONNECTION_TIMEOUT))
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        // So is a 133 between two key closes.
        assertEquals(Verdict.NONE, detector.early(a, BleLinkDriver.GATT_ERROR_133))
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `the count starts over after it fires`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.encrypted(a, 19)
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `closes older than the window do not count`() {
        var clock = 0L
        val detector = BleStaleBondDetector(enabled = true, now = { clock })
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        clock += BleStaleBondDetector.WINDOW_MS + 1
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        clock += BleStaleBondDetector.WINDOW_MS
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `a link to an unbonded device clears the count`() {
        val detector = BleStaleBondDetector(enabled = true)
        detector.encrypted(a, 19)
        detector.onUnbondedLink(a)
        assertEquals(Verdict.NONE, detector.encrypted(a, 19))
        assertEquals(Verdict.STALE, detector.encrypted(a, 19))
    }

    @Test
    fun `off where Android reports missing keys itself`() {
        val detector = BleStaleBondDetector(enabled = false)
        repeat(5) {
            assertEquals(Verdict.NONE, detector.encrypted(a, 19))
            assertEquals(Verdict.NONE, detector.early(a, 19))
        }
    }
}
