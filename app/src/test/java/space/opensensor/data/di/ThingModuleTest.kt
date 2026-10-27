package space.opensensor.data.di

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import space.opensensor.data.history.Metrics
import space.opensensor.family.air.AirFamily
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.thing.OpenSensorBoardKind

class ThingModuleTest {
    @After
    fun restore() = AirFamily.install()

    @Test
    fun theAppListsTheBoardKindAndFeedsItsMetrics() {
        val kinds = ThingModule.provideThingKinds()
        assertEquals(1, kinds.size)
        assertEquals(OpenSensorBoardKind, kinds[OpenSensorBoardKind.id])
        assertNotNull(Metrics.byId(AirFamily.PM25_CORRECTED_ID))
        assertNotNull(DiscoverCollection.BUNDLED.scale("pm25"))
    }
}
