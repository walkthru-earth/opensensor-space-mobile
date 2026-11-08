package space.opensensor.device

import space.opensensor.data.protocol.LocationConfig
import space.opensensor.publicfeed.H3Index
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceLocationTest {
    @Test
    fun everyThingIsAtResolution8() {
        assertEquals(8, DeviceLocationDraft().resolution)
        assertEquals(8, DeviceLocationDraft.from(LocationConfig()).resolution)
        assertEquals(8, DeviceLocationDraft.from(LocationConfig(resolution = 6)).resolution)
        assertFailsWith<IllegalArgumentException> { DeviceLocationDraft().atResolution(9) }
        assertFailsWith<IllegalArgumentException> { DeviceLocationDraft().atResolution(5) }
    }

    @Test
    fun aFinerBoardCellIsCoarsenedAndACoarserOneNeedsAFreshFix() {
        val fine = DeviceLocationDraft.from(LocationConfig(cell = "8928308280fffff", resolution = 9))
        assertEquals("8828308281fffff", fine.cell)
        assertFalse(fine.needsFix)
        val coarse = DeviceLocationDraft.from(LocationConfig(cell = "85283083fffffff", resolution = 5))
        assertEquals("85283083fffffff", coarse.cell)
        assertTrue(coarse.needsFix)
        // A fix taken at another resolution is dropped, one at 8 clears the need.
        assertEquals(coarse, coarse.withFix("8928308280fffff", 9))
        val fixed = coarse.withFix("8828308281fffff", 8)
        assertEquals("8828308281fffff", fixed.cell)
        assertFalse(fixed.needsFix)
        assertEquals(5, H3Index.resolution(H3Index.fromHex(coarse.cell)))
    }

    @Test
    fun unsetOrMalformedLocationReadsAsAnUnsetResolution8Draft() {
        assertEquals(DeviceLocationDraft(), DeviceLocationDraft.from(LocationConfig()))
        assertEquals(DeviceLocationDraft(), DeviceLocationDraft.from(LocationConfig(cell = "not a cell", resolution = 15)))
        assertEquals("", DeviceLocationDraft().atResolution(8).cell)
    }
}
