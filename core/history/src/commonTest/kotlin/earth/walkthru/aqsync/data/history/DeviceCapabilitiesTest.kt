package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    private val waveshareDict = BundledCatalog.templates.first { it.schema.startsWith("waveshare-") }.dict
    private val cores3Dict = BundledCatalog.templates.first { it.schema.startsWith("cores3-") }.dict
    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = waveshareDict)
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = cores3Dict)

    @Test fun noInfoMeansNothingIsAssumed() {
        assertEquals(DeviceCapabilities.NONE, DeviceCapabilities.from(null))
        assertFalse(DeviceCapabilities.NONE.showsParticles(LiveSample(pms = 4, pm25 = 3.0)))
    }

    @Test fun waveshareHasEveryCapability() {
        val caps = DeviceCapabilities.from(waveshare)
        assertEquals(TemperatureSource.AMBIENT, caps.liveProfile?.temperature)
        assertTrue(caps.configurable)
        assertTrue(caps.sensorIdentity)
        assertTrue(caps.batchEstimate)
        assertTrue(caps.showsBatchEstimate(1))
        assertFalse(caps.showsBatchEstimate(0))
        assertFalse(caps.showsBatchEstimate(null))
    }

    @Test fun unknownDictionaryKeepsSchemaFamilyButHasNoLiveProfile() {
        val live = LiveSample(pms = 4, pm25 = 7.0, t = 22.0, rh = 50.0, bstate = 2)
        val caps = DeviceCapabilities.from(waveshare.copy(dict = "0".repeat(64)))
        assertNull(caps.liveProfile)
        assertTrue(caps.sensorIdentity)
        assertFalse(caps.batchEstimate, "an unknown dictionary must not show the estimate")
        assertFalse(caps.showsBatchEstimate(1))
        assertFalse(caps.showsParticles(live))
    }

    @Test fun protocolV1IsNotConfigurable() {
        val caps = DeviceCapabilities.from(cores3)
        assertNotNull(caps.liveProfile)
        assertFalse(caps.configurable)
        assertFalse(caps.sensorIdentity)
        assertFalse(DeviceCapabilities.from(DeviceInfo(schema = "x")).configurable, "absent proto reads as v1")
    }

    @Test fun particlesNeedAReportedSensor() {
        val caps = DeviceCapabilities.from(cores3)
        assertFalse(caps.showsParticles(null))
        assertFalse(caps.showsParticles(LiveSample()))
        assertFalse(caps.showsParticles(LiveSample(pms = 0)))
        assertTrue(caps.showsParticles(LiveSample(pms = 1)))
        assertTrue(caps.showsParticles(LiveSample(pms = 4, pm25 = 12.0)))
    }

    @Test fun cores3ReadsBoardTemperatureAndHasNoBatchEstimate() {
        val caps = DeviceCapabilities.from(cores3)
        assertEquals(TemperatureSource.BOARD, caps.liveProfile?.temperature)
        assertFalse(caps.batchEstimate)
        assertFalse(caps.showsBatchEstimate(1))
    }

    @Test fun aRetiredWaveshareDictionaryHasNoBatchEstimate() {
        val v4 = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"
        val caps = DeviceCapabilities.from(DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4", dict = v4))
        assertNull(caps.liveProfile)
        assertFalse(caps.batchEstimate)
    }
}
