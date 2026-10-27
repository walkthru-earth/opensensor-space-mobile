package space.opensensor.family.air

import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AirFamilyTest {
    init {
        AirFamily.install()
    }

    private val waveshare = assertNotNull(BundledCatalog.template("50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7"))

    @Test
    fun `a template with CF1 PM2_5 and RH derives the corrected PM2_5`() {
        val metric = AirFamily.derive(waveshare).single()
        assertEquals(AirFamily.PM25_CORRECTED_ID, metric.datastream.name)
        assertEquals(listOf(AirFamily.PM25_CF1, AirFamily.RELATIVE_HUMIDITY), metric.datastream.sourceColumns)
        assertEquals(AirFamily.EPA_METHOD, metric.datastream.derived?.method)
        assertEquals(AirFamily.PM25_ATMOSPHERIC, metric.viewOf)
        assertEquals(EpaPm25Correction.correct(20.0, 50.0), metric.formula.valueOf(20.0, 50.0))
    }

    @Test
    fun `a template without RH or CF1 PM2_5 derives nothing`() {
        val noRh = waveshare.copy(datastreams = waveshare.datastreams.filter { it.name != AirFamily.RELATIVE_HUMIDITY })
        val noCf1 = waveshare.copy(datastreams = waveshare.datastreams.filter { it.name != AirFamily.PM25_CF1 })
        assertTrue(AirFamily.derive(noRh).isEmpty())
        assertTrue(AirFamily.derive(noCf1).isEmpty())
    }
}
