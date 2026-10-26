package space.opensensor.designsystem

import space.opensensor.airquality.Pm25Basis
import space.opensensor.data.history.Metrics
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.pm25_corrected
import space.opensensor.designsystem.resources.pm25_raw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #92. Every PM2.5 chart and card says corrected or raw. Other metrics keep their registry label. */
class MetricLabelsTest {
    @Test
    fun eachBasisHasItsOwnLabel() {
        assertEquals(Res.string.pm25_corrected, pm25LabelRes(Pm25Basis.CORRECTED))
        assertEquals(Res.string.pm25_raw, pm25LabelRes(Pm25Basis.RAW))
    }

    @Test
    fun thePm25MetricsSayCorrectedOrRaw() {
        assertEquals(Res.string.pm25_corrected, metricLabelRes(Metrics.PM25_CORRECTED))
        assertEquals(Res.string.pm25_raw, metricLabelRes(Metrics.PM25))
    }

    @Test
    fun otherMetricsKeepTheirRegistryLabel() {
        assertNull(metricLabelRes(Metrics.PM10))
        assertNull(metricLabelRes(Metrics.HUMIDITY))
        assertNull(metricLabelRes(Metrics.PM25_BATCH_ESTIMATE))
    }
}
