package earth.walkthru.aqsync.data.protocol

import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract fixtures from the bundled board templates, not phone captures or hardware verification. */
class LiveProfileTest {
    private val cores3Keys = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg")
    private val waveshareKeys = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct")

    private val cores3 = BundledCatalog.templates.first { it.schema.startsWith("cores3-") }.dict
    private val waveshare = BundledCatalog.templates.first { it.schema.startsWith("waveshare-") }.dict

    @Test
    fun `CoreS3 dictionary reads board temperature from imu_t`() {
        val info = DeviceInfo.parse("""{"schema":"cores3-telemetry-v5","dict":"$cores3"}""")
        val profile = LiveProfiles.resolve(info)!!
        assertEquals(LiveProfiles.CORES3, profile)
        assertEquals(TemperatureSource.BOARD, profile.temperature)
        assertFalse(profile.humidity)
        assertEquals(cores3Keys, profile.keys)
        assertFalse("t" in profile.keys, "CoreS3 has no ambient air temperature")
        val sample = LiveSample.parse("""{"seq":7,"mono":1000,"pms":4,"pm25":5.5,"imu_t":41.2,"als":120,"vbus":5012,"bat":4100,"pct":90,"chg":1}""")
        assertEquals(41.2, sample.imuT!!, 0.0)
        assertNull(sample.t)
        assertEquals(41.2, profile.temperatureOf(sample)!!, 0.0)
    }

    @Test
    fun `Waveshare dictionary reads ambient temperature from t and has humidity and bstate`() {
        val info = DeviceInfo.parse("""{"schema":"waveshare-sim7670g-telemetry-v5","dict":"$waveshare"}""")
        val profile = LiveProfiles.resolve(info)!!
        assertEquals(LiveProfiles.WAVESHARE, profile)
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
        assertEquals(waveshareKeys + "bstate", profile.keys)
        val sample = LiveSample.parse("""{"pms":4,"t":23.7,"rh":58.1,"bstate":2}""")
        assertEquals(23.7, profile.temperatureOf(sample)!!, 0.0)
        assertEquals(58.1, sample.rh!!, 0.0)
        assertEquals(2, sample.bstate)
        assertNull(LiveSample.parse("""{"pms":1}""").rh)
        assertNull(LiveSample.parse("""{"t":22.4,"rh":51.0}""").bstate, "no battery, no bstate")
    }

    @Test
    fun `a board temperature is never read as air temperature or the other way round`() {
        val both = LiveSample.parse("""{"t":23.0,"imu_t":40.0}""")
        assertEquals(40.0, LiveProfiles.CORES3.temperatureOf(both)!!, 0.0)
        assertEquals(23.0, LiveProfiles.WAVESHARE.temperatureOf(both)!!, 0.0)
        assertNull(LiveProfiles.WAVESHARE.temperatureOf(LiveSample.parse("""{"imu_t":40.0}""")))
        assertNull(LiveProfiles.CORES3.temperatureOf(LiveSample.parse("""{"pms":4,"t":31.5}""")), "t is never the CoreS3 board temperature")
        assertNull(LiveProfiles.CORES3.temperatureOf(null))
    }

    @Test
    fun `missing keys read as null and unknown keys are ignored in any order`() {
        val sample = LiveSample.parse("""{"pct":80,"future_key":[1,2],"imu_t":39.5,"seq":3,"nested":{"x":1}}""")
        assertEquals(3L, sample.seq)
        assertEquals(80, sample.pct)
        assertEquals(39.5, sample.imuT!!, 0.0)
        assertNull(sample.pm25)
        assertNull(sample.utc)
        assertNull(sample.pms)
        // A non-finite value is omitted by the firmware, so the key is simply absent.
        assertNull(LiveSample.parse("""{"seq":4}""").imuT)
    }

    @Test
    fun `a newer schema string over a known dictionary keeps its profile`() {
        assertEquals(LiveProfiles.CORES3, LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v6", dict = cores3)))
        assertEquals(LiveProfiles.WAVESHARE, LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v6", dict = waveshare)))
    }

    @Test
    fun `INFO without a dictionary has no LIVE profile`() {
        assertNull(LiveProfiles.resolve(null))
        assertNull(LiveProfiles.resolve(DeviceInfo(fw = "idf-cores3-parquet-v6.12")))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v5")))
    }

    @Test
    fun `an unknown dictionary has no LIVE profile and no key is guessed`() {
        val unknown = DeviceInfo(fw = "idf-cores3-parquet-v9.9", schema = "cores3-telemetry-v5", dict = "0".repeat(64))
        assertNull(LiveProfiles.resolve(unknown))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v5", dict = "unreviewed")))
    }
}
