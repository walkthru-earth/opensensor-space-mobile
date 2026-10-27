package space.opensensor.data.di

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import space.opensensor.airquality.AirDerivedMetrics
import space.opensensor.data.history.Metrics
import space.opensensor.thing.OpenSensorBoardKind

class ThingModuleTest {
    @After
    fun restore() = Metrics.install(Metrics.DEFAULT_PROVIDERS)

    @Test
    fun theAppListsTheBoardKindAndFeedsItsMetrics() {
        val kinds = ThingModule.provideThingKinds()
        assertEquals(1, kinds.size)
        assertEquals(OpenSensorBoardKind, kinds[OpenSensorBoardKind.id])
        assertNotNull(Metrics.byId(AirDerivedMetrics.PM25_CORRECTED_ID))
    }
}
