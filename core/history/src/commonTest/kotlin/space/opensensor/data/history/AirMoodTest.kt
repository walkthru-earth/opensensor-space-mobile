package space.opensensor.data.history

import space.opensensor.airquality.Pm25Basis
import space.opensensor.airquality.WhoGuidelines
import space.opensensor.airquality.WhoStep
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.PmsStatus
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirMoodTest {
    @Test
    fun `six moods follow the WHO 2021 PM2_5 steps with inclusive edges`() {
        assertEquals(WhoStep.entries, AirMood.entries.map { it.step })
        assertEquals(AirMood.FRESH, AirMood.of(0.0))
        assertEquals(AirMood.FRESH, AirMood.of(15.0)) // at or below the guideline meets it
        assertEquals(AirMood.FAIR, AirMood.of(15.1))
        assertEquals(AirMood.FAIR, AirMood.of(25.0))
        assertEquals(AirMood.HAZY, AirMood.of(37.5))
        assertEquals(AirMood.POOR, AirMood.of(50.0))
        assertEquals(AirMood.SMOKY, AirMood.of(75.0))
        assertEquals(AirMood.THICK, AirMood.of(75.1))
        assertEquals(AirMood.THICK, AirMood.of(999.0))
        assertNull(AirMood.ofOrNull(Double.NaN))
        // Every mood carries its non-colour carriers, in plain copy.
        AirMood.entries.forEach {
            assertTrue(it.advice.isNotBlank() && it.comparison.isNotBlank())
            listOf(it.advice, it.comparison).forEach { text ->
                assertTrue(text.none { c -> c == '\u2014' || c == '\u2013' || c == ':' || c == ';' }, text)
            }
        }
    }

    @Test
    fun `an edge value belongs to the lower step and agrees with the registry`() {
        // PM values are integers in the files, so exactly 15, 25, 50 or 75 happen every day. WHO
        // states each level as "at or below", so the edge itself meets the step below it, in
        // `AirMood.of`, `MetricSpec.moodOf` and the UI colours that delegate to them.
        val edges = WhoGuidelines.PM25.edges
        assertEquals(listOf(15.0, 25.0, 37.5, 50.0, 75.0), edges)
        edges.forEachIndexed { i, edge ->
            val above = edge.nextUp()
            assertEquals(AirMood.entries[i], AirMood.of(edge))
            assertEquals(AirMood.entries[i + 1], AirMood.of(above))
            assertEquals(AirMood.of(edge), AirMetrics.PM25.moodOf(edge))
            assertEquals(AirMood.of(above), AirMetrics.PM25.moodOf(above))
        }
    }

    @Test
    fun `PM10 is banded on its own WHO scale and other metrics stay neutral`() {
        assertEquals(WhoGuidelines.PM10, AirMetrics.PM10.healthScale)
        assertEquals(AirMood.FRESH, AirMetrics.PM10.moodOf(45.0))
        assertEquals(AirMood.FAIR, AirMetrics.PM10.moodOf(46.0))
        assertEquals(AirMood.HAZY, AirMetrics.PM10.moodOf(75.0))
        assertEquals(AirMood.THICK, AirMetrics.PM10.moodOf(151.0))
        assertEquals(WhoStep.IT2, AirMetrics.PM10.stepOf(100.0))
        assertNull(AirMetrics.PM10.moodOf(Double.NaN))
        for (m in listOf(AirMetrics.PM1, AirMetrics.PM25_BATCH_ESTIMATE, AirMetrics.HUMIDITY, AirMetrics.AMBIENT_TEMP)) {
            assertFalse(m.banded)
            assertNull(m.moodOf(40.0))
        }
        assertFailsWith<IllegalArgumentException> { AirMetrics.AMBIENT_TEMP.copy(healthScale = WhoGuidelines.PM25) }
    }

    @Test
    fun `the sensor's own state comes before any number`() {
        assertEquals(NowReading.Waiting, NowReading.of(null))
        val warming = LiveFixtures.waveshare("""{"seq":1,"mono":1,"pms":1,"pm25":40}""")
        val r = NowReading.of(warming)
        assertTrue(r is NowReading.SensorNotReady && r.status == PmsStatus.WARMING)
        val valid = LiveFixtures.waveshare("""{"seq":2,"mono":2,"pms":4,"pm25":22}""")
        val v = NowReading.of(valid) as NowReading.Reading
        assertEquals(AirMood.FAIR, v.mood)
        assertEquals(22.0, v.primary, 0.0)
        // pms 4 with a null pm25 is no reading now, so nothing to show.
        assertEquals(NowReading.Waiting, NowReading.of(LiveFixtures.waveshare("""{"seq":3,"mono":3,"pms":4,"pm25":null}""")))
        // An overflow sample has every key null, so the last reading is cleared.
        assertEquals(NowReading.Waiting, NowReading.of(LiveFixtures.waveshare("""{"seq":null,"mono":null,"utc":null,"pms":null,"pm25":null}""")))
        // An unknown dictionary has no LIVE reading.
        assertEquals(NowReading.Waiting, NowReading.of(DeviceCapabilities.from(LiveFixtures.waveshare.copy(dict = "0".repeat(64))).reading(LiveSample.parse("""{"pms":4,"pm25":22}"""))))
    }

    @Test
    fun `the live reading shows corrected PM2_5 when the sample has a valid RH`() {
        // #92. CF=1 40 at 50 percent RH corrects to 27.64, from atmospheric 30.
        val humid = NowReading.of(LiveFixtures.waveshare("""{"seq":4,"mono":4,"pms":4,"pm25":30,"c25":40,"rh":50}""")) as NowReading.Reading
        assertEquals(Pm25Basis.CORRECTED, humid.basis)
        assertEquals(27.64, humid.primary, 1e-6)
        assertEquals(30.0, humid.raw!!, 0.0)
        assertEquals(AirMood.HAZY, humid.mood)
        // No RH (CoreS3 hides rh), a null RH, or an RH outside 0 to 100, shows raw labelled raw.
        val dryReadings = listOf(
            LiveFixtures.cores3("""{"seq":5,"mono":5,"pms":4,"pm25":30,"c25":40,"rh":50}"""),
            LiveFixtures.waveshare("""{"seq":6,"mono":6,"pms":4,"pm25":30,"c25":40,"rh":null}"""),
            LiveFixtures.waveshare("""{"seq":7,"mono":7,"pms":4,"pm25":30,"c25":40,"rh":104}"""),
        )
        for (live in dryReadings) {
            val dry = NowReading.of(live) as NowReading.Reading
            assertEquals(Pm25Basis.RAW, dry.basis)
            assertEquals(30.0, dry.primary, 0.0)
        }
    }
}
