package space.opensensor.data.di

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import space.opensensor.OpenSensorApplication
import space.opensensor.data.history.Metrics
import space.opensensor.family.air.AirFamily
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKinds
import javax.inject.Inject

class ThingModuleTest {
    /** Starts from an empty catalog and no bundled scales, so only ThingModule can fill them. */
    @Before
    fun clear() {
        Metrics.install(emptyList())
        DiscoverCollection.installBundled("""{"type":"Collection","id":"discover-cells-bundled"}""")
    }

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

    /**
     * Dagger injects an Application's fields in declaration order, so the first injected field is
     * built first. Building ThingKinds installs the families, so it must come before the workers,
     * the scheduler and History, which read the metric catalog.
     */
    @Test
    fun theApplicationInjectsThingKindsFirst() {
        val injected = OpenSensorApplication::class.java.declaredFields.filter { it.isAnnotationPresent(Inject::class.java) }
        assertEquals(ThingKinds::class.java, injected.first().type)
    }
}
