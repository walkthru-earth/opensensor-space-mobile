package space.opensensor.device

import space.opensensor.data.protocol.LocationConfig
import space.opensensor.publicfeed.H3Index
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceLocationTest {
    @Test
    fun everyThingDefaultsToResolution8() {
        assertEquals(8, DeviceLocationDraft().resolution)
        assertEquals(8, DeviceLocationDraft.from(LocationConfig()).resolution)
        assertEquals(6, DeviceLocationDraft.from(LocationConfig(resolution = 6)).resolution)
    }

    @Test
    fun precisionChangesNeverRecoverDiscardedCoordinates() {
        val fine = DeviceLocationDraft(cell = "8928308280fffff", resolution = 9)
        val coarse = fine.atResolution(5)
        assertEquals(5, H3Index.resolution(H3Index.fromHex(coarse.cell)))
        assertFalse(coarse.needsFix)
        val increase = coarse.atResolution(9)
        assertEquals(coarse.cell, increase.cell)
        assertTrue(increase.needsFix)
        assertEquals(increase, increase.withFix(fine.cell, 5))
        assertFalse(increase.withFix(fine.cell, 9).needsFix)
        val retainedCoarser = DeviceLocationDraft(cell = coarse.cell, resolution = 7).atResolution(6)
        assertEquals(coarse.cell, retainedCoarser.cell)
        assertFalse(retainedCoarser.needsFix)
    }

    @Test
    fun unsetLocationDefaultsToCoarseResolutionFive() {
        assertEquals(DeviceLocationDraft(), DeviceLocationDraft.from(LocationConfig()))
        assertEquals("", DeviceLocationDraft().atResolution(15).cell)
    }
}
