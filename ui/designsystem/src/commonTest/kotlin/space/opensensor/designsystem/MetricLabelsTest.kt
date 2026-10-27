package space.opensensor.designsystem

import space.opensensor.airquality.Pm25Basis
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.pm25_corrected
import space.opensensor.designsystem.resources.pm25_raw
import space.opensensor.model.Basis
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
        assertEquals(Res.string.pm25_corrected, metricLabelRes(AirMetrics.PM25_CORRECTED))
        assertEquals(Res.string.pm25_raw, metricLabelRes(AirMetrics.PM25))
    }

    @Test
    fun otherMetricsKeepTheirRegistryLabel() {
        assertNull(metricLabelRes(AirMetrics.PM10))
        assertNull(metricLabelRes(AirMetrics.HUMIDITY))
        assertNull(metricLabelRes(AirMetrics.PM25_BATCH_ESTIMATE))
    }

    @Test
    fun aRawOrCorrectedViewOfAnotherQuantityKeepsItsOwnLabel() {
        val raw = MetricSpec(id = "soil_moisture_percent", column = "soil_moisture_percent", label = "Soil moisture", unit = "%", basis = Basis.RAW)
        val corrected = raw.copy(id = "soil_moisture_compensated", label = "Soil moisture compensated", basis = Basis.CORRECTED, property = raw.property)
        assertNull(metricLabelRes(raw))
        assertNull(metricLabelRes(corrected))
    }
}
