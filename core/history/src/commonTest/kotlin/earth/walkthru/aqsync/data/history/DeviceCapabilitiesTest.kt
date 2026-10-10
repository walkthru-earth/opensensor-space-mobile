package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    private val waveshareV2 = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v2", dict = LiveProfiles.WAVESHARE_V2_DICTIONARY)
    private val waveshareV1 = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v1", dict = LiveProfiles.WAVESHARE_DICTIONARY)
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v3", dict = LiveProfiles.CORES3_DICTIONARY)

    @Test fun noInfoMeansNothingIsAssumed() {
        assertEquals(DeviceCapabilities.NONE, DeviceCapabilities.from(null))
        assertFalse(DeviceCapabilities.NONE.showsParticles(LiveSample(pms = 4, pm25 = 3.0)))
    }

    @Test fun waveshareV2HasEveryCapability() {
        val caps = DeviceCapabilities.from(waveshareV2)
        assertEquals(TemperatureSource.AMBIENT, caps.liveProfile?.temperature)
        assertTrue(caps.configurable)
        assertTrue(caps.sensorIdentity)
        assertTrue(caps.batchEstimate)
        assertTrue(caps.showsBatchEstimate(1))
        assertFalse(caps.showsBatchEstimate(0))
        assertFalse(caps.showsBatchEstimate(null))
    }

    @Test fun waveshareV1StoresTheStickerButHasNoBatchEstimate() {
        val caps = DeviceCapabilities.from(waveshareV1)
        assertTrue(caps.sensorIdentity)
        assertFalse(caps.batchEstimate)
        assertFalse(caps.showsBatchEstimate(1))
    }

    @Test fun unreviewedDictionaryKeepsSchemaFamilyButNoLiveProfile() {
        val caps = DeviceCapabilities.from(waveshareV2.copy(dict = "0".repeat(64)))
        assertNull(caps.liveProfile)
        assertTrue(caps.sensorIdentity)
        assertFalse(caps.batchEstimate, "an unreviewed dictionary must not show the estimate")
        assertFalse(caps.showsParticles(LiveSample(pms = 4)))
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

    @Test fun locationSchemasPreserveReviewedLiveMeanings() {
        val wave = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v3", dict = LiveProfiles.WAVESHARE_V3_DICTIONARY)
        val core = DeviceInfo(proto = 3, schema = "cores3-telemetry-v4", dict = LiveProfiles.CORES3_V4_DICTIONARY)
        assertEquals(TemperatureSource.AMBIENT, DeviceCapabilities.from(wave).liveProfile?.temperature)
        assertTrue(DeviceCapabilities.from(wave).batchEstimate)
        assertEquals(TemperatureSource.BOARD, DeviceCapabilities.from(core).liveProfile?.temperature)
        assertFalse(DeviceCapabilities.from(core).batchEstimate)
        assertNull(DeviceCapabilities.from(wave.copy(dict = "0".repeat(64))).liveProfile)
        assertFalse(DeviceCapabilities.from(wave.copy(dict = "0".repeat(64))).batchEstimate)
    }
}
