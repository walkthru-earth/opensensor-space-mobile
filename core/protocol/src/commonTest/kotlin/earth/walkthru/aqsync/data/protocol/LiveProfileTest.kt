package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Contract fixtures from the aq-parquet field tables, not phone captures or hardware verification. */
class LiveProfileTest {
    private val cores3Keys = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg")
    private val waveshareKeys = setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct")

    @Test
    fun `CoreS3 dictionary reads board temperature from imu_t`() {
        val info = DeviceInfo.parse("""{"schema":"cores3-telemetry-v5","dict":"${LiveProfiles.CORES3_DICTIONARY}"}""")
        val profile = LiveProfiles.resolve(info)!!
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
    fun `Waveshare dictionary reads ambient temperature from t and has humidity`() {
        val info = DeviceInfo.parse("""{"schema":"waveshare-sim7670g-telemetry-v4","dict":"${LiveProfiles.WAVESHARE_DICTIONARY}"}""")
        val profile = LiveProfiles.resolve(info)!!
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
        assertEquals(waveshareKeys, profile.keys)
        val sample = LiveSample.parse("""{"pms":4,"t":23.7,"rh":58.1}""")
        assertEquals(23.7, profile.temperatureOf(sample)!!, 0.0)
        assertEquals(58.1, sample.rh!!, 0.0)
        assertNull(LiveSample.parse("""{"pms":1}""").rh)
    }

    @Test
    fun `a board temperature is never read as air temperature or the other way round`() {
        val both = LiveSample.parse("""{"t":23.0,"imu_t":40.0}""")
        assertEquals(40.0, LiveProfiles.CORES3.temperatureOf(both)!!, 0.0)
        assertEquals(23.0, LiveProfiles.WAVESHARE.temperatureOf(both)!!, 0.0)
        assertNull(LiveProfiles.WAVESHARE.temperatureOf(LiveSample.parse("""{"imu_t":40.0}""")))
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
        assertSame(LiveProfiles.CORES3, LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v6", dict = LiveProfiles.CORES3_DICTIONARY)))
        assertSame(LiveProfiles.WAVESHARE, LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v5", dict = LiveProfiles.WAVESHARE_DICTIONARY)))
    }

    @Test
    fun `older CoreS3 dictionaries read board temperature from t`() {
        listOf(LiveProfiles.CORES3_V4_DICTIONARY, LiveProfiles.CORES3_V3_DICTIONARY).forEach { dict ->
            val profile = LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v4", dict = dict))!!
            assertSame(LiveProfiles.CORES3_LEGACY, profile)
            assertEquals(TemperatureSource.BOARD, profile.temperature)
            assertFalse(profile.humidity)
            assertEquals(cores3Keys - "imu_t" + "t", profile.keys)
            assertEquals(31.5, profile.temperatureOf(LiveSample.parse("""{"pms":4,"t":31.5}"""))!!, 0.0)
            assertNull(profile.temperatureOf(LiveSample.parse("""{"imu_t":40.0}""")))
        }
    }

    @Test
    fun `older Waveshare dictionaries keep the ambient profile`() {
        listOf(LiveProfiles.WAVESHARE_V3_DICTIONARY, LiveProfiles.WAVESHARE_V2_DICTIONARY, LiveProfiles.WAVESHARE_V1_DICTIONARY).forEach { dict ->
            assertSame(LiveProfiles.WAVESHARE, LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v3", dict = dict)))
        }
    }

    @Test
    fun `Waveshare v5 dictionary adds bstate and keeps ambient t and rh`() {
        assertSame(LiveProfiles.WAVESHARE_V5, LiveProfiles.resolve(DeviceInfo(dict = LiveProfiles.WAVESHARE_V5_FIRST_DICTIONARY)))
        val profile = LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v5", dict = LiveProfiles.WAVESHARE_V5_DICTIONARY))!!
        assertSame(LiveProfiles.WAVESHARE_V5, profile)
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
        assertEquals(waveshareKeys + "bstate", profile.keys)
        val sample = LiveSample.parse("""{"t":22.4,"rh":51.0,"bstate":2}""")
        assertEquals(22.4, profile.temperatureOf(sample)!!, 0.0)
        assertEquals(2, sample.bstate)
        assertNull(LiveSample.parse("""{"t":22.4,"rh":51.0}""").bstate, "no battery, no bstate")
    }

    @Test
    fun `CoreS3 board temperature falls back to t when imu_t is missing`() {
        assertEquals(31.5, LiveProfiles.CORES3.temperatureOf(LiveSample.parse("""{"pms":4,"t":31.5}"""))!!, 0.0)
        assertEquals(40.0, LiveProfiles.CORES3.temperatureOf(LiveSample.parse("""{"t":31.5,"imu_t":40.0}"""))!!, 0.0)
        assertNull(LiveProfiles.CORES3.temperatureOf(LiveSample.parse("""{"pms":4}""")))
    }

    @Test
    fun `INFO without a dictionary has no LIVE profile`() {
        assertNull(LiveProfiles.resolve(null))
        assertNull(LiveProfiles.resolve(DeviceInfo(fw = "idf-cores3-parquet-v6.12")))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "cores3-telemetry-v5")))
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "waveshare-sim7670g-telemetry-v6"), LiveSample.parse("""{"pms":4,"t":22.0}""")))
    }

    private val unknown = DeviceInfo(fw = "idf-cores3-parquet-v9.9", schema = "waveshare-sim7670g-telemetry-v9", dict = "0".repeat(64))

    @Test
    fun `unknown dictionary with ambient keys still shows PM with ambient temperature and humidity`() {
        val sample = LiveSample.parse("""{"seq":1,"pms":4,"pm25":7,"n03":900,"t":22.4,"rh":51.0,"bat":4100}""")
        val profile = LiveProfiles.resolve(unknown, sample)!!
        assertFalse(profile.reviewed)
        assertEquals(TemperatureSource.AMBIENT, profile.temperature)
        assertTrue(profile.humidity)
        assertEquals(setOf("seq", "pms", "pm25", "n03", "t", "rh", "bat"), profile.keys)
        assertEquals(22.4, profile.temperatureOf(sample)!!, 0.0)
        assertEquals(7.0, sample.pm25!!, 0.0)
    }

    @Test
    fun `unknown dictionary reads imu_t as board temperature and never t as board`() {
        val board = LiveSample.parse("""{"pms":4,"pm25":7,"imu_t":40.0}""")
        val profile = LiveProfiles.resolve(unknown, board)!!
        assertEquals(TemperatureSource.BOARD, profile.temperature)
        assertFalse(profile.humidity)
        assertEquals(40.0, profile.temperatureOf(board)!!, 0.0)
        assertNull(profile.temperatureOf(LiveSample.parse("""{"pms":4,"t":31.5}""")), "t is ambient, never board, without a reviewed profile")
        val both = LiveSample.parse("""{"pms":4,"t":22.0,"imu_t":40.0}""")
        val ambient = LiveProfiles.resolve(unknown, both)!!
        assertEquals(TemperatureSource.AMBIENT, ambient.temperature)
        assertEquals(22.0, ambient.temperatureOf(both)!!, 0.0)
    }

    @Test
    fun `unknown LIVE keys are ignored`() {
        val sample = LiveSample.parse("""{"pms":4,"pm25":7,"pm4":9,"t2":55.0,"temp":60.0,"imu_t":40.0}""")
        val profile = LiveProfiles.resolve(unknown, sample)!!
        assertEquals(setOf("pms", "pm25", "imu_t"), profile.keys)
        assertEquals(TemperatureSource.BOARD, profile.temperature)
        assertEquals(40.0, profile.temperatureOf(sample)!!, 0.0)
    }

    @Test
    fun `the vocabulary is exactly the keys LiveSample reads`() {
        val every = LiveProfiles.VOCABULARY.joinToString(",", "{", "}") { "\"$it\":1" }
        assertEquals(LiveProfiles.VOCABULARY, LiveProfiles.keysOf(LiveSample.parse(every)))
        assertEquals(emptySet(), LiveProfiles.keysOf(null))
    }

    @Test
    fun `known dictionaries keep their reviewed profile whatever the sample sends`() {
        val sample = LiveSample.parse("""{"pms":4,"t":22.0,"imu_t":40.0,"rh":50.0}""")
        assertSame(LiveProfiles.CORES3, LiveProfiles.resolve(DeviceInfo(dict = LiveProfiles.CORES3_V5_DICTIONARY), sample))
        assertSame(LiveProfiles.WAVESHARE_V5, LiveProfiles.resolve(DeviceInfo(dict = LiveProfiles.WAVESHARE_V5_DICTIONARY), sample))
        assertTrue(LiveProfiles.CORES3.reviewed)
    }
}
