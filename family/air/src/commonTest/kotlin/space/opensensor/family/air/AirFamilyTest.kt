package space.opensensor.family.air

import space.opensensor.model.AppProfile
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Family
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

    private val profile = assertNotNull(BundledCatalog.profile(waveshare.dict))

    private fun particles(profile: AppProfile): Boolean = AirFamily.PARTICLES in AirFamily.capabilitiesOf(Thing.join("t", waveshare, profile))

    private val pm25Columns = profile.scales.filterValues { it == AirFamily.PM25_SCALE }.keys

    @Test
    fun `an air profile whose LIVE shows PM2_5 has the particle cards`() {
        assertTrue(pm25Columns.isNotEmpty())
        assertTrue(particles(profile))
    }

    @Test
    fun `a profile of another family has no particle cards`() {
        assertFalse(particles(profile.copy(family = Family.GENERIC)))
    }

    @Test
    fun `an air profile without a PM2_5 in LIVE has no particle cards`() {
        assertFalse(particles(profile.copy(hidden = profile.hidden + pm25Columns)), "hidden PM2.5 columns are not in LIVE")
        assertFalse(particles(profile.copy(scales = profile.scales - pm25Columns)), "no column on the PM2.5 scale")
    }
}
