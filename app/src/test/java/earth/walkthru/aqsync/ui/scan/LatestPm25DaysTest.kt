package earth.walkthru.aqsync.ui.scan

import earth.walkthru.aqsync.airquality.Pm25Basis
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.local.database.MetricBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestPm25DaysTest {
    private fun day(station: String, metric: String, startS: Long, mean: Float, valid: Int = 100) = MetricBucket(
        station = station, metricId = metric, resolution = 86_400L, startEpochS = startS,
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
