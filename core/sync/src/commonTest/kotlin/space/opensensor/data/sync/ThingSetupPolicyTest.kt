package space.opensensor.data.sync

import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.SetupAck
import space.opensensor.model.ManifestStep
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThingSetupPolicyTest {
    private val cell = "8828308281fffff"
    private val noFlags = 0
    private val none = SetupManifest.NONE

    private fun page(mobility: String?, cell: String?, location: Boolean = true, setup: Int? = null, setupV: Int? = null, ack: Int? = null): DeviceConfig {
        val loc = if (location) """"location":{"cell":${cell?.let { "\"$it\"" } ?: "null"},"resolution":8,"country":""}""" else null
        val fields = listOfNotNull(
            mobility?.let { """"mobility":"$it"""" },
            setup?.let { """"setup":$it""" },
            setupV?.let { """"setup_v":$it""" },
            ack?.let { """"setup_ack":$it""" },
        )
        val thing = if (fields.isEmpty()) null else """"thing":{${fields.joinToString(",")}}"""
        return DeviceConfig.parse("{" + listOfNotNull(loc, thing).joinToString(",") + "}")
    }

    @Test
    fun `bit 6 alone needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP, null, none))
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP or Advertisement.FLAG_NEW_FILES, page("fixed", cell), none))
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP, page("moving", ""), none))
    }

    @Test
    fun `page 1 setup 1 needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell, setup = 1), none))
        assertTrue(ThingSetupPolicy.needsSetup(null, page("moving", "", setup = 1), none))
    }

    @Test
    fun `page 1 setup 0 never lowers the location floor`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "", setup = 0), none))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell, setup = 0), none))
        // Bit 6 from the advert still wins.
        assertTrue(ThingSetupPolicy.needsSetup(Advertisement.FLAG_SETUP, page("fixed", cell, setup = 0), none))
    }

    @Test
    fun `a fixed Thing with a cell needs nothing`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell), none))
        // A coarser cell kept from before the res 8 rule is still a cell.
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "85283083fffffff"), none))
    }

    @Test
    fun `a fixed Thing with a null or malformed cell needs setup`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", null), none))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", "not a cell"), none))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", null, location = false), none))
    }

    @Test
    fun `absent mobility on a page that reports location counts as fixed`() {
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page(null, ""), none))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page(null, cell), none))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, DeviceConfig.parse("{}"), none))
    }

    @Test
    fun `a moving Thing never needs a cell and an unknown mobility claims nothing`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("moving", ""), none))
        assertFalse(ThingSetupPolicy.needsSetup(null, page("moving", null, location = false), none))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("drifting", ""), none))
    }

    @Test
    fun `a declared step stays pending until the device holds its bit`() {
        val manifest = SetupManifest(setup = listOf(ManifestStep(StepId.WIFI)))
        val v = SetupAck.version(listOf("wifi"))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell), manifest))
        assertTrue(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell, setupV = v + 1, ack = 1), manifest))
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, page("fixed", cell, setupV = v, ack = 1), manifest))
    }

    @Test
    fun `page 1 unavailable claims nothing without bit 6`() {
        assertFalse(ThingSetupPolicy.needsSetup(noFlags, null, none))
        assertFalse(ThingSetupPolicy.needsSetup(null, null, none))
        // The other flag bits say nothing about setup.
        assertFalse(ThingSetupPolicy.needsSetup(0x3f or 0x80, null, none))
    }
}
