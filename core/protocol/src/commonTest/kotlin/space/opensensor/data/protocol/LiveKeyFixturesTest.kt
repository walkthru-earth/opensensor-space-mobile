package space.opensensor.data.protocol

import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The digests and LIVE keys frozen in aq-parquet `tools/fixtures/live_keys/cores3.json` and
 * `waveshare.json`, copied as test data in board key order. The app bundles one template per board,
 * for its current digest, and its `live_keys` list the keys in the same order (aq-parquet #86). When
 * firmware ships a new digest, copy aq-parquet `docs/shared/templates` again, move the old digest to
 * [retired] and copy the new build here. The weekly `live-key-drift` workflow fails while aq-parquet
 * main has a digest missing from this file.
 */
class LiveKeyFixturesTest {
    private data class FixtureBuild(val board: String, val digest: String, val keys: List<String>)

    private val current = listOf(
        FixtureBuild("cores3", "4f80c26cde8d7ea78c2160225d98386009a8651d9a049b4bf68a75da96015e94", listOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "n5", "n10", "imu_t", "als", "vbus", "bat", "pct", "chg", "t", "rh")),
        FixtureBuild("waveshare", "6f567b38fecc165f544626168931b3dc3d674482da87ed78cb0bec5bf3dc6ceb", listOf("seq", "mono", "utc", "pms", "c1", "c25", "c10", "pm1", "pm25", "pm10", "n03", "n05", "n1", "n25", "t", "rh", "bat", "pct", "bstate")),
    )

    /** Released digests the app no longer bundles. Boards on them get no LIVE view until reflashed. */
    private val retired = listOf(
        "20f850852413abb1165f3f7ca95b4831a0e90a34ae7c7fcb41c44b108d3513c3", // cores3-telemetry-v2 and v3
        "f35d73981ca65d9de587a4ec7988ccea19e71dce988c69eb4bb99713f2352bb3", // CoreS3 v6.11, cores3-telemetry-v4
        "22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65", // CoreS3 v6.12, LIVE keys outside the digest
        "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b", // CoreS3 v6.13, cores3-telemetry-v5
        "e840c946c10378d28831b38f71daf66981d2ea99cf47ccba80fcd2e64f920ffd", // waveshare-sim7670g-telemetry-v1
        "715685cf83ac8d6975fde845ce58fd15fa6eaaf3df32644ec15dfc89875d2e9e", // waveshare-sim7670g-telemetry-v2
        "7cd1c69da4ecd728dde2736e4c55063d9707ef53cdba1745b8896efcfd60f5da", // waveshare-sim7670g-telemetry-v3
        "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82", // Waveshare v2.6, waveshare-sim7670g-telemetry-v4
        "d0d0129440e04108d8ca1f9b9555f1b45316ee0ccf3af0fcf7c3a88c8c0796b8", // first Waveshare v2.7, LIVE keys outside the digest
        "67688d0fd2e86053c1c66b961a1446ad3c225ee71823e0fadb771b645c7183ff", // Waveshare v2.7, waveshare-sim7670g-telemetry-v5
    )

    /** The CoreS3 contract maps `t` and `rh` but they are always null there, so its profile hides them. */
    private val cores3AlwaysNull = setOf("t", "rh")

    @Test
    fun `every current digest has a bundled template with the fixture LIVE keys in board order`() {
        current.forEach { build ->
            val template = assertNotNull(BundledCatalog.template(build.digest), build.board)
            assertEquals(build.keys, template.liveKeys.values.toList(), build.board)
        }
        assertEquals(current.map { it.digest }.toSet(), BundledCatalog.templates.map { it.dict }.toSet())
    }

    @Test
    fun `every current digest shows the fixture LIVE keys of its board`() {
        current.forEach { build ->
            val station = assertNotNull(BundledCatalog.station("station", build.digest), "${build.board} has no LIVE station")
            val expected = if (build.board == "cores3") build.keys.toSet() - cores3AlwaysNull else build.keys.toSet()
            assertEquals(expected, station.live.keys, build.board)
        }
    }

    @Test
    fun `a retired digest has no template and no LIVE station`() {
        retired.forEach { digest ->
            assertNull(BundledCatalog.template(digest), digest)
            assertNull(BundledCatalog.station("station", digest), digest)
        }
    }
}
