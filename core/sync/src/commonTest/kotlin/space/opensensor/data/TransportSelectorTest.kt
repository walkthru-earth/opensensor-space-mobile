package space.opensensor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransportSelectorTest {
    private val dev = "a1b2c3d4e5f6"

    @Test
    fun noStoredTokenMeansBluetooth() {
        assertNull(TransportSelector.lanPort(hasLanToken = false, knownDeviceId = dev, advertisedDev = dev, advertisedPort = 47390, knownPort = 47390))
    }

    @Test
    fun advertisedPortWinsOverTheStoredOne() {
        assertEquals(47391, TransportSelector.lanPort(true, dev, dev, advertisedPort = 47391, knownPort = 47390))
    }

    @Test
    fun outOfRangeAdvertisedPortFallsBackToTheStoredOne() {
        assertEquals(47390, TransportSelector.lanPort(true, dev, dev, advertisedPort = 0, knownPort = 47390))
        assertEquals(47390, TransportSelector.lanPort(true, dev, dev, advertisedPort = 70_000, knownPort = 47390))
        assertNull(TransportSelector.lanPort(true, dev, dev, advertisedPort = 0, knownPort = null))
    }

    @Test
    fun anotherBoardUnderTheSameStationIsNotTriedOverLan() {
        assertNull(TransportSelector.lanPort(true, dev, "ffffffffffff", 47390, 47390))
        assertNull(TransportSelector.lanPort(true, dev, null, 47390, 47390))
    }

    @Test
    fun hardwareIdComparisonIgnoresCase() {
        assertEquals(47390, TransportSelector.lanPort(true, dev, dev.uppercase(), 47390, null))
    }

    @Test
    fun aDeviceWithoutPinnedHardwareMayUseLan() {
        assertEquals(47390, TransportSelector.lanPort(true, null, null, 47390, null))
    }
}
