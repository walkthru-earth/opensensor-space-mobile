package space.opensensor.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.opensensor.data.history.Metrics
import space.opensensor.data.local.database.MetricBucket
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics

class LatestDaysTest {
    init {
        AirFamily.install()
    }

    private fun day(thing: String, metric: String, startS: Long, mean: Float, valid: Int = 100) = MetricBucket(
        thing = thing, metricId = metric, resolution = 86_400L, startEpochS = startS,
        rows = 100, validRows = valid, estimatedRows = 0, mean = mean, min = mean, max = mean, sampleIntervalMs = 10_000,
    )

    private val corrected = AirMetrics.PM25_CORRECTED.id
    private val raw = AirMetrics.PM25.id

    @Test
    fun `each thing shows its main metric, corrected when that is the newest day and raw otherwise`() {
        val rows = Metrics.all.map { m ->
            when (m.id) {
                corrected -> listOf(day("humid", corrected, 172_800, 8f), day("old-rh", corrected, 0, 7f))
                raw -> listOf(
                    day("humid", raw, 172_800, 11f),
                    day("cores3", raw, 172_800, 20f),
                    day("old-rh", raw, 86_400, 12f),
                    day("empty", raw, 86_400, 0f, valid = 0),
                )
                AirMetrics.HUMIDITY.id -> listOf(day("empty", AirMetrics.HUMIDITY.id, 86_400, 50f))
                else -> emptyList()
            }
        }
        val out = latestDays(rows)
        assertEquals(corrected to 8f, out.getValue("humid").let { it.metric.id to it.mean })
        // No humidity sensor, so raw.
        assertEquals(raw to 20f, out.getValue("cores3").let { it.metric.id to it.mean })
        // The humidity sensor stopped, the newest day is raw only, so it shows raw.
        assertEquals(raw to 12f, out.getValue("old-rh").let { it.metric.id to it.mean })
        // Only humidity has data, so humidity is its main metric, never a PM2.5 day without rows.
        assertEquals(AirMetrics.HUMIDITY.id, out.getValue("empty").metric.id)
        assertNull(out["none"])
    }
}
