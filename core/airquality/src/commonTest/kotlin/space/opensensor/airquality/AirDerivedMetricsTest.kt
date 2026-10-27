package space.opensensor.airquality

import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AirDerivedMetricsTest {
    private val waveshare = assertNotNull(BundledCatalog.template("a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809"))

    @Test
    fun `a template with CF1 PM2_5 and RH derives the corrected PM2_5`() {
        val metric = AirDerivedMetrics.derive(waveshare).single()
        assertEquals(AirDerivedMetrics.PM25_CORRECTED_ID, metric.datastream.name)
        assertEquals(listOf(AirDerivedMetrics.PM25_CF1, AirDerivedMetrics.RELATIVE_HUMIDITY), metric.datastream.sourceColumns)
        assertEquals(AirDerivedMetrics.EPA_METHOD, metric.datastream.derived?.method)
        assertEquals(AirDerivedMetrics.PM25_ATMOSPHERIC, metric.viewOf)
        assertEquals(EpaPm25Correction.correct(20.0, 50.0), metric.formula.valueOf(20.0, 50.0))
    }

    @Test
    fun `a template without RH or CF1 PM2_5 derives nothing`() {
        val noRh = waveshare.copy(datastreams = waveshare.datastreams.filter { it.name != AirDerivedMetrics.RELATIVE_HUMIDITY })
        val noCf1 = waveshare.copy(datastreams = waveshare.datastreams.filter { it.name != AirDerivedMetrics.PM25_CF1 })
        assertTrue(AirDerivedMetrics.derive(noRh).isEmpty())
        assertTrue(AirDerivedMetrics.derive(noCf1).isEmpty())
    }
}
