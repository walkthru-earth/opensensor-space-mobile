package space.opensensor.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.opensensor.data.ble.BleScanner
import space.opensensor.data.ble.ScannedDevice
import space.opensensor.data.protocol.Advertisement
import space.opensensor.devices.NearbySensor

/** The Android feed of the setup watch (#272), from the advert bytes to the My devices row input. */
class ScanSetupFlagsTest {
    private fun advert(version: Int, flags: Int) = byteArrayOf(version.toByte(), flags.toByte(), 0, 0, 0, 0, 0x34, 0x12)

    @Test
    fun `the scanner reads the flags byte of a version 1 advert`() {
        assertEquals(Advertisement.FLAG_SETUP or Advertisement.FLAG_SD, BleScanner.advertFlags(advert(1, Advertisement.FLAG_SETUP or Advertisement.FLAG_SD)))
        assertEquals(0, BleScanner.advertFlags(advert(1, 0)))
    }

    @Test
    fun `no service data, an unknown version or a short payload carries no flags`() {
        assertNull(BleScanner.advertFlags(null))
        assertNull(BleScanner.advertFlags(advert(2, Advertisement.FLAG_SETUP)))
        assertNull(BleScanner.advertFlags(advert(1, Advertisement.FLAG_SETUP).copyOf(7)))
    }

    @Test
    fun `a scan result keeps its flags and when it was heard`() {
        val seen = ScannedDevice("AA:BB", "OS-1", -55, bonded = true, lastSeenEpochMs = 42, advertFlags = Advertisement.FLAG_SETUP)
        assertEquals(NearbySensor("AA:BB", "OS-1", -55, bonded = true, advertFlags = Advertisement.FLAG_SETUP, seenMs = 42), seen.toNearbySensor())
        assertNull(ScannedDevice("CC", null, -70, false, 7).toNearbySensor().advertFlags)
    }
}
