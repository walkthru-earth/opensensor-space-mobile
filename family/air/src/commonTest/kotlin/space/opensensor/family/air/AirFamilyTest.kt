package space.opensensor.family.air

import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Thing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AirFamilyTest {
    init {
        AirFamily.install()
    }

    private val waveshare = assertNotNull(BundledCatalog.template("a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809"))

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

    private val overlay = assertNotNull(BundledCatalog.profile(waveshare.dict))

    private fun particles(template: BoardTemplate = waveshare, profile: AppProfile = overlay): Boolean =
        AirFamily.PARTICLES in AirFamily.capabilitiesOf(Thing.join("t", template, profile))

    private val pm25Columns = Thing.join("t", waveshare, overlay).datastreams.filter { it.scale?.id == WhoGuidelines.PM25.id }.map { it.name }.toSet()

    @Test
    fun `an air profile whose LIVE shows PM2_5 has the particle cards`() {
        assertTrue(pm25Columns.isNotEmpty())
        assertTrue(particles())
    }

    private val crowpanel = BundledCatalog.templates.single { it.id == "crowpanel-epaper-telemetry-v1" }

    @Test
    fun `the CrowPanel is air by its properties with no bundled profile`() {
        val thing = Thing.join("c", crowpanel)
        assertTrue(AirFamily.PARTICLES in AirFamily.capabilitiesOf(thing))
        assertFalse(AirFamily.BATCH_ESTIMATE in AirFamily.capabilitiesOf(thing))
        assertEquals("pm25_mass_ug_m3", thing.air?.pm25?.name)
    }

    @Test
    fun `a Sensirion board gets no EPA correction and a Plantower board does`() {
        assertTrue(AirFamily.derive(crowpanel).isEmpty())
        assertEquals(false, AirColumns.of(crowpanel)?.epa)
        assertEquals(true, AirColumns.of(waveshare)?.epa)
        val cores3 = assertNotNull(BundledCatalog.template("3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"))
        assertEquals(AirFamily.PM25_ATMOSPHERIC, AirFamily.derive(cores3).single().viewOf)
    }

    @Test
    fun `the PM2_5 metrics resolve per Thing`() {
        val panel = Thing.join("c", crowpanel)
        assertEquals("pm25_mass_ug_m3", AirMetrics.pm25(panel)?.column)
        assertEquals(null, AirMetrics.pm25Corrected(panel), "a Sensirion PM2.5 has no EPA view")
        val board = Thing.join("w", waveshare, overlay)
        assertEquals(AirFamily.PM25_ATMOSPHERIC, AirMetrics.pm25(board)?.column)
        assertEquals(AirFamily.PM25_CORRECTED_ID, AirMetrics.pm25Corrected(board)?.id)
    }

    @Test
    fun `a CF1 PM2_5 from a sensor the correction was not fitted on derives nothing`() {
        val other = waveshare.copy(
            datastreams = waveshare.datastreams.map { ds ->
                if (ds.name == AirFamily.PM25_CF1) ds.copy(sensor = ds.sensor?.copy(procedure = "other-sensor")) else ds
            },
        )
        assertTrue(AirFamily.derive(other).isEmpty())
    }

    @Test
    fun `a template without a PM2_5 property has no air surface`() {
        val noPm = waveshare.copy(datastreams = waveshare.datastreams.map { if (it.property?.id == AirProperty.PM25) it.copy(property = null) else it })
        assertEquals(null, AirColumns.of(noPm))
        assertEquals(null, AirFamily.rowCheck(noPm))
        assertTrue(AirFamily.capabilitiesOf(Thing.join("t", noPm)).isEmpty())
    }

    @Test
    fun `the air columns are found by property in size order`() {
        val air = assertNotNull(AirColumns.of(assertNotNull(BundledCatalog.template("3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"))))
        assertEquals(AirFamily.PARTICLE_BINS, air.bins.map { it.name })
        assertEquals(AirFamily.PARTICLE_BINS.first(), air.gt03?.name)
        assertEquals(listOf(">0.3", ">0.5", ">1", ">2.5", ">5", ">10"), air.bins.map { AirProperty.binLabel(it.property!!.id) })
        assertEquals(AirFamily.PM25_CF1, air.cf1[1]?.name)
        assertEquals(AirFamily.RELATIVE_HUMIDITY, air.humidity?.name)
    }

    @Test
    fun `an air profile without a PM2_5 in LIVE has no particle cards`() {
        assertFalse(particles(profile = overlay.copy(hidden = overlay.hidden + pm25Columns)), "hidden PM2.5 columns are not in LIVE")
        val unscaled = waveshare.copy(datastreams = waveshare.datastreams.map { if (it.name in pm25Columns) it.copy(property = null) else it })
        assertFalse(particles(template = unscaled), "no column on the PM2.5 scale")
    }
}
