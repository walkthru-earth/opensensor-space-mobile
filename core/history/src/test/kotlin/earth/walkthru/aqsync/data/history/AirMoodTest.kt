package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirMoodTest {
    @Test
    fun `bands match the chart's PM2_5 bands at their edges`() {
        assertEquals(AirMood.FRESH, AirMood.of(0.0))
        assertEquals(AirMood.FRESH, AirMood.of(14.9))
        assertEquals(AirMood.FINE, AirMood.of(15.0)) // WHO 2021 24-h guideline is the first edge
        assertEquals(AirMood.HAZY, AirMood.of(35.0))
        assertEquals(AirMood.SMOKY, AirMood.of(55.0))
        assertEquals(AirMood.THICK, AirMood.of(150.0))
        assertEquals(AirMood.THICK, AirMood.of(999.0))
        // Every mood carries its non-colour carriers.
        AirMood.entries.forEach { assertTrue(it.title.isNotBlank() && it.advice.isNotBlank() && it.comparison.isNotBlank()) }
    }

    @Test
    fun `the sensor's own state comes before any number`() {
        assertEquals(NowReading.Waiting, NowReading.of(null))
        val warming = LiveSample.parse("""{"seq":1,"mono":1,"pms":1,"pm25":40}""")
        val r = NowReading.of(warming)
        assertTrue(r is NowReading.SensorNotReady && r.status == PmsStatus.WARMING)
        val valid = LiveSample.parse("""{"seq":2,"mono":2,"pms":4,"pm25":22}""")
        val v = NowReading.of(valid) as NowReading.Reading
        assertEquals(AirMood.FINE, v.mood)
        assertEquals(22.0, v.pm25, 0.0)
        // pms 4 but no pm25 key: absent means null, so nothing to show.
        assertEquals(NowReading.Waiting, NowReading.of(LiveSample.parse("""{"seq":3,"mono":3,"pms":4}""")))
    }
}
