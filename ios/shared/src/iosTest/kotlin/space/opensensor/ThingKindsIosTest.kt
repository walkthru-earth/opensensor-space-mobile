package space.opensensor

import space.opensensor.data.history.Metrics
import space.opensensor.family.air.AirFamily
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.thing.OpenSensorBoardKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ThingKindsIosTest {
    // Starts from an empty catalog and no bundled scales, so only launchThingKinds can fill them.
    init {
        Metrics.install(emptyList())
        DiscoverCollection.installBundled("""{"type":"Collection","id":"discover-cells-bundled"}""")
    }

    // iosThingKinds is a cached lazy, so a later test in this process would keep the empty catalog.
    @AfterTest
    fun restoreAirFamily() {
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
        assertNotNull(DiscoverCollection.BUNDLED.scale(AirFamily.PM25_SCALE))
    }
}
