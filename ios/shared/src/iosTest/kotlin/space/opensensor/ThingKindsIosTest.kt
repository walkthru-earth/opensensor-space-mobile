package space.opensensor

import space.opensensor.data.history.Metrics
import space.opensensor.family.air.AirFamily
import space.opensensor.thing.OpenSensorBoardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ThingKindsIosTest {
    init {
        AirFamily.install()
    }

    @Test
    fun theAppListsTheBoardKindOnce() {
        launchThingKinds()
        val kinds = iosThingKinds
        assertEquals(1, kinds.size)
        assertEquals(OpenSensorBoardKind, kinds[OpenSensorBoardKind.id])
        launchThingKinds()
        assertSame(kinds, iosThingKinds)
        assertNotNull(Metrics.byId(AirFamily.PM25_CORRECTED_ID))
    }
}
