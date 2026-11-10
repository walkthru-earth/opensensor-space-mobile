package space.opensensor.data.history

import space.opensensor.data.history.LiveFixtures.cores3
import space.opensensor.data.history.LiveFixtures.waveshare
import space.opensensor.data.history.LiveFixtures.waveshareDict
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.batchEstimate
import space.opensensor.family.air.showsAirCards
import space.opensensor.family.air.showsBatchEstimate
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Capability
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.Thing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    init {
        AirFamily.install()
    }

    @Test fun noInfoMeansNothingIsAssumed() {
        assertEquals(DeviceCapabilities.NONE, DeviceCapabilities.from(null))
        assertNull(DeviceCapabilities.NONE.reading(LiveSample.parse("""{"pms":4,"pm25":3}""")))
        assertFalse(DeviceCapabilities.NONE.showsAirCards(LiveSample.parse("""{"pms":4,"pm25":3}""")))
    }

    @Test fun waveshareHasEveryCapability() {
        val caps = DeviceCapabilities.from(waveshare)
        assertEquals(AirFamily.FAMILY, caps.thing?.profile?.family)
        assertEquals("ambient_temperature_c", caps.temperature?.name)
        assertTrue(caps.ambientTemperature)
        assertEquals("relative_humidity_percent", caps.humidity?.name)
        assertTrue(caps.configurable)
        assertTrue(caps.sensorIdentity)
        assertTrue(caps.batchEstimate)
        assertTrue(caps.showsBatchEstimate(1))
        assertFalse(caps.showsBatchEstimate(0))
        assertFalse(caps.showsBatchEstimate(null))
    }

    @Test fun unknownDictionaryHasNoThingAndNothingIsGuessed() {
        val live = LiveSample.parse("""{"pms":4,"pm25":7.0,"t":22.0,"rh":50.0,"bstate":2}""")
        val caps = DeviceCapabilities.from(waveshare.copy(dict = "0".repeat(64)))
        assertNull(caps.thing)
        assertNull(caps.reading(live))
        assertFalse(caps.sensorIdentity, "identity comes from the template column, never the schema string")
        assertFalse(caps.batchEstimate, "an unknown dictionary must not show the estimate")
        assertFalse(caps.showsBatchEstimate(1))
        assertFalse(caps.showsAirCards(live))
        assertNull(caps.temperature)
    }

    @Test fun infoWithoutADictionaryHasNoThing() {
        assertNull(DeviceCapabilities.from(DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5")).thing)
    }

    @Test fun protocolV1IsNotConfigurable() {
        val caps = DeviceCapabilities.from(cores3)
        assertNotNull(caps.thing)
        assertFalse(caps.configurable)
        assertFalse(caps.sensorIdentity)
        assertFalse(DeviceCapabilities.from(DeviceInfo(schema = "x")).configurable, "absent proto reads as v1")
    }

    @Test fun particlesNeedAReportedSensorAndANullStatusKeepsThem() {
        val caps = DeviceCapabilities.from(cores3)
        assertFalse(caps.showsAirCards(null))
        assertFalse(caps.showsAirCards(LiveSample()), "an absent pms key means the board has no such field")
        assertTrue(caps.showsAirCards(LiveSample.parse("""{"pms":null,"pm25":null}""")), "a null status is no reading now")
        assertFalse(caps.showsAirCards(LiveSample.parse("""{"pms":0}""")))
        assertTrue(caps.showsAirCards(LiveSample.parse("""{"pms":1}""")))
        assertTrue(caps.showsAirCards(LiveSample.parse("""{"pms":4,"pm25":12.0}""")))
    }

    @Test fun cores3ReadsBoardTemperatureAndHasNoBatchEstimate() {
        val caps = DeviceCapabilities.from(cores3)
        assertEquals("imu_temperature_c", caps.temperature?.name)
        assertFalse(caps.ambientTemperature)
        assertNull(caps.humidity)
        assertFalse(caps.batchEstimate)
        assertFalse(caps.showsBatchEstimate(1))
    }

    @Test fun aRetiredWaveshareDictionaryHasNoBatchEstimate() {
        val v4 = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"
        val caps = DeviceCapabilities.from(DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4", dict = v4))
        assertNull(caps.thing)
        assertFalse(caps.batchEstimate)
    }

    @Test
    fun `the air boards carry the particles capability and CoreS3 has no config`() {
        assertEquals(
            setOf(Capability.LIVE, Capability.CONFIG, Capability.SENSOR_IDENTITY, AirFamily.BATCH_ESTIMATE, AirFamily.PARTICLES),
            DeviceCapabilities.from(waveshare).capabilities,
        )
        val coreS3 = DeviceCapabilities.from(cores3)
        assertTrue(AirFamily.PARTICLES in coreS3)
        assertFalse(Capability.CONFIG in coreS3)
        assertEquals(emptySet(), DeviceCapabilities.NONE.capabilities)
    }

    @Test
    fun `a template without a reviewed profile gets no family capability`() {
        // The Waveshare template writes the batch estimate column, so only the derived gate keeps it out.
        val caps = DeviceCapabilities.from(waveshare, profiles = { null })
        assertNull(caps.thing)
        assertEquals(setOf(Capability.CONFIG, Capability.SENSOR_IDENTITY), caps.capabilities)
        assertFalse(caps.batchEstimate, "an unreviewed template must not show the estimate")
    }

    @Test
    fun `providers run only after the derived gate`() {
        val probe = Capability("probe")
        val provider = object : DerivedMetricProvider {
            override fun derive(template: BoardTemplate) = emptyList<DerivedMetric>()

            override fun capabilitiesOf(thing: Thing) = setOf(probe)
        }
        val template = assertNotNull(BundledCatalog.template(waveshareDict))
        val reviewed = Thing.join("t", template, BundledCatalog.profile(template.dict))
        val derived = Thing.join("t", template, profile = null)
        assertTrue(derived.profile.isDerived)
        assertTrue(probe in DeviceCapabilities.capabilitiesOf(reviewed, proto = 3, providers = listOf(provider)))
        assertFalse(probe in DeviceCapabilities.capabilitiesOf(derived, proto = 3, providers = listOf(provider)))
    }
}
