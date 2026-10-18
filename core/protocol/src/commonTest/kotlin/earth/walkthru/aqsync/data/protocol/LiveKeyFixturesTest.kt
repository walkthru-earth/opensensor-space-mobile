package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every digest and LIVE key set frozen in aq-parquet `tools/fixtures/live_keys/cores3.json` and
 * `waveshare.json`, copied as test data. When firmware ships a new digest, copy its builds here from
 * the fixture, and this test fails until [LiveProfiles.resolve] maps it.
 */
class LiveKeyFixturesTest {
    private data class FixtureBuild(val board: String, val digest: String, val firmware: String, val commit: String, val keys: Set<String>)

    private val builds = listOf(
        FixtureBuild("cores3", "f35d73981ca65d9de587a4ec7988ccea19e71dce988c69eb4bb99713f2352bb3", "idf-cores3-parquet-v6.11", "95ef33c", setOf("seq", "mono", "utc", "pms", "pm1", "pm25", "pm10", "c1", "c25", "c10", "n03", "n05", "n1", "n25", "n5", "n10", "t", "bat", "pct", "chg", "vbus", "als")),
        FixtureBuild("cores3", "22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65", "idf-cores3-parquet-v6.12", "e166978", setOf("seq", "mono", "utc", "pms", "pm1", "pm25", "pm10", "c1", "c25", "c10", "n03", "n05", "n1", "n25", "n5", "n10", "t", "bat", "pct", "chg", "vbus", "als")),
        FixtureBuild("cores3", "22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65", "idf-cores3-parquet-v6.12", "d2a840c", setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg", "t", "rh")),
        FixtureBuild("cores3", "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b", "idf-cores3-parquet-v6.13", "", setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg", "t", "rh")),
        FixtureBuild("waveshare", "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82", "idf-waveshare-parquet-v2.6", "e166978", setOf("seq", "mono", "utc", "pms", "pm1", "pm25", "pm10", "c1", "c25", "c10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct")),
        FixtureBuild("waveshare", "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82", "idf-waveshare-parquet-v2.6", "d2a840c", setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct")),
        FixtureBuild("waveshare", "d0d0129440e04108d8ca1f9b9555f1b45316ee0ccf3af0fcf7c3a88c8c0796b8", "idf-waveshare-parquet-v2.7", "9698536", setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct", "bstate")),
        FixtureBuild("waveshare", "67688d0fd2e86053c1c66b961a1446ad3c225ee71823e0fadb771b645c7183ff", "idf-waveshare-parquet-v2.7", "", setOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct", "bstate")),
    )

    /** The CoreS3 contract maps `t` and `rh` but they are always null there, so its profiles leave them out. */
    private val cores3AlwaysNull = setOf("t", "rh")

    @Test
    fun `every fixture digest resolves to a profile that covers its LIVE keys`() {
        builds.forEach { build ->
            val label = "${build.firmware} ${build.commit} ${build.digest.take(8)}"
            val profile = assertNotNull(LiveProfiles.resolve(DeviceInfo(dict = build.digest)), "$label has no LIVE profile")
            val expected = if (build.board == "cores3") build.keys - cores3AlwaysNull else build.keys
            assertTrue(profile.keys.containsAll(expected), "$label misses ${expected - profile.keys}")
            val source = if (build.board == "cores3") TemperatureSource.BOARD else TemperatureSource.AMBIENT
            assertEquals(source, profile.temperature, label)
            assertEquals(build.board == "waveshare", profile.humidity, label)
        }
    }

    @Test
    fun `a CoreS3 build that sends the IMU temperature as t still shows board temperature`() {
        builds.filter { it.board == "cores3" && "imu_t" !in it.keys }.forEach { build ->
            val profile = LiveProfiles.resolve(DeviceInfo(dict = build.digest))!!
            assertEquals(31.5, profile.temperatureOf(LiveSample.parse("""{"pms":4,"t":31.5}"""))!!, 0.0, build.commit)
        }
    }

    @Test
    fun `CoreS3 v6_13 keeps the unchanged CoreS3 profile and reads imu_t`() {
        val info = DeviceInfo.parse("""{"fw":"idf-cores3-parquet-v6.13","schema":"cores3-telemetry-v5","dict":"${LiveProfiles.CORES3_V5_DICTIONARY}"}""")
        assertSame(LiveProfiles.CORES3, LiveProfiles.resolve(info))
        val sample = LiveSample.parse("""{"seq":1,"mono":1,"utc":1000,"pms":4,"pm25":7,"n5":0,"n10":0,"imu_t":40.0,"als":1,"vbus":1,"bat":1,"pct":1,"chg":1}""")
        assertEquals(40.0, LiveProfiles.CORES3.temperatureOf(sample)!!, 0.0)
    }
}
