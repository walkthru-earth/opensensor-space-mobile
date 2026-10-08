package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
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
            assertTrue(it.title.isNotBlank() && it.advice.isNotBlank() && it.comparison.isNotBlank())
            listOf(it.title, it.advice, it.comparison).forEach { text ->
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
            assertEquals(AirMood.of(edge), Metrics.PM25.moodOf(edge))
            assertEquals(AirMood.of(above), Metrics.PM25.moodOf(above))
        }
    }

    @Test
    fun `PM10 is banded on its own WHO scale and other metrics stay neutral`() {
        assertEquals(WhoGuidelines.PM10, Metrics.PM10.healthScale)
        assertEquals(AirMood.FRESH, Metrics.PM10.moodOf(45.0))
        assertEquals(AirMood.FAIR, Metrics.PM10.moodOf(46.0))
        assertEquals(AirMood.HAZY, Metrics.PM10.moodOf(75.0))
        assertEquals(AirMood.THICK, Metrics.PM10.moodOf(151.0))
        assertEquals(WhoStep.IT2, Metrics.PM10.stepOf(100.0))
        assertNull(Metrics.PM10.moodOf(Double.NaN))
        for (m in listOf(Metrics.PM1, Metrics.PM25_BATCH_ESTIMATE, Metrics.HUMIDITY, Metrics.AMBIENT_TEMP)) {
            assertFalse(m.banded)
            assertNull(m.moodOf(40.0))
        }
        assertFailsWith<IllegalArgumentException> { Metrics.AMBIENT_TEMP.copy(healthScale = WhoGuidelines.PM25) }
    }

    @Test
    fun `the sensor's own state comes before any number`() {
        assertEquals(NowReading.Waiting, NowReading.of(null))
        val warming = LiveSample.parse("""{"seq":1,"mono":1,"pms":1,"pm25":40}""")
        val r = NowReading.of(warming)
        assertTrue(r is NowReading.SensorNotReady && r.status == PmsStatus.WARMING)
        val valid = LiveSample.parse("""{"seq":2,"mono":2,"pms":4,"pm25":22}""")
        val v = NowReading.of(valid) as NowReading.Reading
        assertEquals(AirMood.FAIR, v.mood)
        assertEquals(22.0, v.pm25, 0.0)
        // pms 4 but no pm25 key: absent means null, so nothing to show.
        assertEquals(NowReading.Waiting, NowReading.of(LiveSample.parse("""{"seq":3,"mono":3,"pms":4}""")))
    }
}
