package space.opensensor.data.sync

import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThingSetupPolicyTest {
    private val cell = "8828308281fffff"
    private val noFlags = 0

    private fun page(mobility: String?, cell: String?, location: Boolean = true): DeviceConfig {
        val loc = if (location) """"location":{"cell":${cell?.let { "\"$it\"" } ?: "null"},"resolution":8,"country":""}""" else null
        val thing = mobility?.let { """"thing":{"mobility":"$it"}""" }
        return DeviceConfig.parse("{" + listOfNotNull(loc, thing).joinToString(",") + "}")
    }

    @Test
    fun `bit 6 alone needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP, null))
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP or Advertisement.FLAG_NEW_FILES, page("fixed", cell)))
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP, page("moving", "")))
    }

    @Test
    fun `bit 6 clear and page 1 unset is the current firmware case and needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "")))
        assertTrue(ThingSetupPolicy.needsSetup(null, page("fixed", "")))
    }

    @Test
    fun `a fixed Thing with a cell needs nothing`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell)))
        // A coarser cell kept from before the res 8 rule is still a cell.
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "85283083fffffff")))
    }

    @Test
    fun `a fixed Thing with a null or malformed cell needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", null)))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "not a cell")))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", null, location = false)))
    }

    @Test
    fun `absent mobility on a board that reports location counts as fixed`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page(null, "")))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page(null, cell)))
    }

    @Test
    fun `absent mobility and no location claims nothing`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, DeviceConfig.parse("{}")))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, DeviceConfig.parse("""{"wifi":{"on":1}}""")))
    }

    @Test
    fun `a moving Thing never needs a cell`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("moving", "")))
        assertFalse(ThingSetupPolicy.needsSetup(null, page("moving", null, location = false)))
    }

    @Test
    fun `an unknown mobility claims nothing`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("drifting", "")))
    }

    @Test
    fun `page 1 unavailable claims nothing without bit 6`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, null))
        assertFalse(ThingSetupPolicy.needsSetup(null, null))
        // The other flag bits say nothing about setup.
        assertFalse(ThingSetupPolicy.needsSetup(0x3f or 0x80, null))
    }
}
