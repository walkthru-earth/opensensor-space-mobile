package space.opensensor.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.opensensor.airquality.Pm25Basis
import space.opensensor.data.history.Metrics
import space.opensensor.data.local.database.MetricBucket

class LatestPm25DaysTest {
    private fun day(thing: String, metric: String, startS: Long, mean: Float, valid: Int = 100) = MetricBucket(
        thing = thing, metricId = metric, resolution = 86_400L, startEpochS = startS,
        rows = 100, validRows = valid, estimatedRows = 0, mean = mean, min = mean, max = mean, sampleIntervalMs = 10_000,
    )

    @Test
    fun `the devices line shows the corrected day when it is the newest and raw labelled raw otherwise`() {
        val corrected = listOf(day("humid", Metrics.PM25_CORRECTED.id, 172_800, 8f), day("old-rh", Metrics.PM25_CORRECTED.id, 0, 7f))
        val raw = listOf(
            day("humid", Metrics.PM25.id, 172_800, 11f),
            day("cores3", Metrics.PM25.id, 172_800, 20f),
            day("old-rh", Metrics.PM25.id, 86_400, 12f),
            day("empty", Metrics.PM25.id, 86_400, 0f, valid = 0),
        )
        val out = latestPm25Days(corrected, raw)
        assertEquals(8f to Pm25Basis.CORRECTED, out.getValue("humid").let { it.first.mean to it.second })
        // No humidity sensor, so raw.
        assertEquals(20f to Pm25Basis.RAW, out.getValue("cores3").let { it.first.mean to it.second })
        // The humidity sensor stopped, the newest day is raw only, so it shows raw.
        assertEquals(12f to Pm25Basis.RAW, out.getValue("old-rh").let { it.first.mean to it.second })
        assertNull(out["empty"])
    }
}
