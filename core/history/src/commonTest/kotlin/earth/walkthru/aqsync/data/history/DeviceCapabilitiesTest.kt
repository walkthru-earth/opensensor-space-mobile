package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.LiveFixtures.cores3
import earth.walkthru.aqsync.data.history.LiveFixtures.waveshare
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveSample
import space.opensensor.model.Family
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    @Test fun noInfoMeansNothingIsAssumed() {
        assertEquals(DeviceCapabilities.NONE, DeviceCapabilities.from(null))
        assertNull(DeviceCapabilities.NONE.reading(LiveSample.parse("""{"pms":4,"pm25":3}""")))
        assertFalse(DeviceCapabilities.NONE.showsParticles(LiveSample.parse("""{"pms":4,"pm25":3}""")))
    }

    @Test fun waveshareHasEveryCapability() {
        val caps = DeviceCapabilities.from(waveshare)
        assertEquals(Family.AIR, caps.station?.profile?.family)
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

    @Test fun unknownDictionaryHasNoStationAndNothingIsGuessed() {
        val live = LiveSample.parse("""{"pms":4,"pm25":7.0,"t":22.0,"rh":50.0,"bstate":2}""")
        val caps = DeviceCapabilities.from(waveshare.copy(dict = "0".repeat(64)))
        assertNull(caps.station)
        assertNull(caps.reading(live))
        assertFalse(caps.sensorIdentity, "identity comes from the template column, never the schema string")
        assertFalse(caps.batchEstimate, "an unknown dictionary must not show the estimate")
        assertFalse(caps.showsBatchEstimate(1))
        assertFalse(caps.showsParticles(live))
        assertNull(caps.temperature)
    }

    @Test fun infoWithoutADictionaryHasNoStation() {
        assertNull(DeviceCapabilities.from(DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5")).station)
    }

    @Test fun protocolV1IsNotConfigurable() {
        val caps = DeviceCapabilities.from(cores3)
        assertNotNull(caps.station)
        assertFalse(caps.configurable)
        assertFalse(caps.sensorIdentity)
        assertFalse(DeviceCapabilities.from(DeviceInfo(schema = "x")).configurable, "absent proto reads as v1")
    }

    @Test fun particlesNeedAReportedSensorAndANullStatusKeepsThem() {
        val caps = DeviceCapabilities.from(cores3)
        assertFalse(caps.showsParticles(null))
        assertFalse(caps.showsParticles(LiveSample()), "an absent pms key means the board has no such field")
        assertTrue(caps.showsParticles(LiveSample.parse("""{"pms":null,"pm25":null}""")), "a null status is no reading now")
        assertFalse(caps.showsParticles(LiveSample.parse("""{"pms":0}""")))
        assertTrue(caps.showsParticles(LiveSample.parse("""{"pms":1}""")))
        assertTrue(caps.showsParticles(LiveSample.parse("""{"pms":4,"pm25":12.0}""")))
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
        assertNull(caps.station)
        assertFalse(caps.batchEstimate)
    }
}
