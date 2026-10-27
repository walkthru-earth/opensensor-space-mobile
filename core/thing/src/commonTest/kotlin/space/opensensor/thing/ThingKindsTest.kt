package space.opensensor.thing

import space.opensensor.airquality.AirDerivedMetrics
import space.opensensor.data.history.Metrics
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.model.Capability
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.publicfeed.BundledSampleFeed
import space.opensensor.thing.testkit.ThingKindContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A kind for the registry and gate checks, with [source] and no evidence of its own. */
class FakeKind(
    id: String,
    override val source: DataSource,
    override val derivedMetrics: List<DerivedMetricProvider> = emptyList(),
) : ThingKind {
    override val id = KindId(id)
    override val contractVersion = 1

    override fun recognize(evidence: Evidence): Recognition? = null
}

val healthKind = FakeKind("health", DataSource.PrivateRead(object : PrivateSourcePort {}))
val feedKind = FakeKind("public-feed", DataSource.RemoteFeed(BundledSampleFeed { error("no bundled file in this test") }))
val localOnlyKind = FakeKind("local-only", DataSource.PullSync(PublishPolicy.Never))

class ThingKindsTest {
    /** The CoreS3 dictionary, pinned. */
    private val coreS3Dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"

    @Test
    fun theBoardKindPassesTheContract() {
        ThingKindContract.check(
            OpenSensorBoardKind,
            recognized = listOf(
                Evidence.BleAdvert(listOf(DeviceGatt.SERVICE.uppercase())),
                Evidence.LanService(LanProtocol.SERVICE_TYPE),
                Evidence.LanService(LanProtocol.SERVICE_TYPE_DOT),
                Evidence.Info(DeviceInfo(proto = 3, dict = coreS3Dict)),
            ),
            foreign = listOf(
                Evidence.BleAdvert(listOf("0000180f-0000-1000-8000-00805f9b34fb")),
                Evidence.BleAdvert(emptyList()),
                Evidence.LanService("_http._tcp"),
                Evidence.Info(DeviceInfo()),
            ),
        )
    }

    @Test
    fun kindsThatNeverPublishPassTheContract() {
        for (kind in listOf(healthKind, feedKind, localOnlyKind)) ThingKindContract.check(kind, recognized = emptyList(), foreign = emptyList())
    }

    @Test
    fun boardInfoGivesItsThingAndCapabilities() {
        val thing = "3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13"
        val recognition = assertNotNull(OpenSensorBoardKind.recognize(Evidence.Info(DeviceInfo(proto = 3, dict = coreS3Dict, thing = thing))))
        assertEquals(thing, recognition.thing)
        assertTrue(Capability.LIVE in recognition.capabilities)
        assertTrue(Capability.CONFIG in recognition.capabilities)
    }

    @Test
    fun repeatedKindIdsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ThingKinds(listOf(OpenSensorBoardKind, FakeKind("opensensor-board", DataSource.PullSync(PublishPolicy.Never)))) }
    }

    @Test
    fun recognizeAsksEachKindInOrder() {
        val kinds = ThingKinds(listOf(healthKind, OpenSensorBoardKind))
        assertEquals(OpenSensorBoardKind.id, kinds.recognize(Evidence.LanService(LanProtocol.SERVICE_TYPE))?.kind)
        assertNull(kinds.recognize(Evidence.LanService("_http._tcp")))
        assertEquals(OpenSensorBoardKind, kinds[KindId("opensensor-board")])
        assertNull(kinds[KindId("phone")])
    }

    @Test
    fun theKindsFeedTheMetricCatalog() {
        val kinds = ThingKinds(listOf(OpenSensorBoardKind, FakeKind("air-again", DataSource.PullSync(PublishPolicy.Never), listOf(AirDerivedMetrics))))
        assertEquals(listOf<DerivedMetricProvider>(AirDerivedMetrics), kinds.metricProviders)
        try {
            ThingKinds(listOf(healthKind)).installMetrics()
            assertNull(Metrics.byId(AirDerivedMetrics.PM25_CORRECTED_ID))
            kinds.installMetrics()
            assertEquals(listOf<DerivedMetricProvider>(AirDerivedMetrics), Metrics.providers)
            assertNotNull(Metrics.byId(AirDerivedMetrics.PM25_CORRECTED_ID))
        } finally {
            Metrics.install(Metrics.DEFAULT_PROVIDERS)
        }
    }
}
