package space.opensensor

import space.opensensor.airquality.AirDerivedMetrics
import space.opensensor.data.history.Metrics
import space.opensensor.thing.OpenSensorBoardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ThingKindsIosTest {
    @Test
    fun theAppListsTheBoardKindOnce() {
        val kinds = launchThingKinds()
        assertEquals(1, kinds.size)
        assertEquals(OpenSensorBoardKind, kinds[OpenSensorBoardKind.id])
        assertSame(kinds, launchThingKinds())
        assertNotNull(Metrics.byId(AirDerivedMetrics.PM25_CORRECTED_ID))
    }
}
