package space.opensensor.family.air

import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.DiscoverCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The PM scales Discover shows for a feed with no Collection name each step as the registry does,
 * in English and Arabic, with the registry's bounds and colours.
 */
class AirDiscoverTest {
    init {
        AirFamily.install()
    }

    @Test
    fun theBundledPmScalesAreTheRegistryScales() {
        for ((property, id) in listOf("pm25" to WhoGuidelines.PM25_ID, "pm10" to WhoGuidelines.PM10_ID)) {
            val shown = assertNotNull(DiscoverCollection.BUNDLED.scale(property), property)
            val registry = assertNotNull(BundledCatalog.scales[id], id)
            assertEquals(registry.steps, shown.steps, id)
            assertEquals("Within the WHO guideline", shown.steps.first().label.get("en"), id)
            assertEquals("فوق كل أهداف منظمة الصحة العالمية", shown.steps.last().label.get("ar"), id)
        }
    }
}
