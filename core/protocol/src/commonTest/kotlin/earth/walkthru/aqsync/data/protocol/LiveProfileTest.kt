package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveProfileTest {
    @Test
    fun `CoreS3 dictionary retains board temperature for both schema versions`() {
        for (schema in listOf("cores3-telemetry-v2", "cores3-telemetry-v3")) {
            val profile = LiveProfiles.resolve(DeviceInfo(schema = schema, dict = LiveProfiles.CORES3_DICTIONARY))!!
            assertEquals(TemperatureSource.BOARD, profile.temperature)
            assertFalse(profile.humidity)
        }
    }

    /** Contract fixture, not a phone capture or hardware verification. */
    @Test
    fun `Waveshare LIVE temperature is ambient and humidity is nullable`() {
        val info = DeviceInfo.parse("""{"schema":"waveshare-sim7670g-telemetry-v1","dict":"${LiveProfiles.WAVESHARE_DICTIONARY}"}""")
        val profile = LiveProfiles.resolve(info)!!
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
        val sample = LiveSample.parse("""{"pms":4,"t":23.7,"rh":58.1}""")
        assertEquals(23.7, sample.t!!, 0.0)
        assertEquals(58.1, sample.rh!!, 0.0)
        assertNull(LiveSample.parse("""{"pms":1}""").rh)
    }

    @Test
    fun `Waveshare v2 dictionary retains ambient interpretation`() {
        val profile = LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v2", dict = LiveProfiles.WAVESHARE_V2_DICTIONARY))!!
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
    }

    @Test
    fun `unknown or changed dictionary never borrows a temperature interpretation`() {
        assertNull(LiveProfiles.resolve(null))
        assertNull(LiveProfiles.resolve(DeviceInfo(fw = "cores3-parquet-v6")))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v3")))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v3", dict = LiveProfiles.WAVESHARE_DICTIONARY)))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v2", dict = LiveProfiles.WAVESHARE_DICTIONARY)))
    }
}
