package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The bundled stac-opensensor property and scale registries, looked up by URI and by id. */
class PropertyRegistryTest {
    private val properties = BundledCatalog.properties
    private val scales = BundledCatalog.scales

    private val temperature = "http://vocab.nerc.ac.uk/collection/P07/current/CFSN0023/"
    private val humidity = "http://vocab.nerc.ac.uk/collection/P07/current/CFSN0413/"
    private val fine = "http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/"
    private val carbonDioxide = "http://vocab.nerc.ac.uk/standard_name/mole_fraction_of_carbon_dioxide_in_air/"

    @Test
    fun aListedUriResolvesToItsEntry() {
        val entry = properties[temperature]!!
        assertEquals("Air temperature", entry.label.get("en"))
        assertEquals("درجة حرارة الهواء", entry.label.get("ar"))
        assertEquals("درجة الحرارة", entry.shortLabel!!.get("ar"))
        // A label with no Arabic falls back to English.
        assertEquals("Acceleration X", properties["urn:walkthru-earth:property:acceleration_x"]!!.label.get("ar"))
        assertEquals(DisplayGroup.CLIMATE, entry.group)
        assertEquals("Cel", entry.unit)
        assertEquals(1, entry.decimals)
        assertEquals("RH", properties[humidity]!!.symbol)
        assertSame(entry, properties.of(ObservedProperty(temperature)))
    }

    @Test
    fun anUnknownUriReturnsNull() {
        assertNull(properties["urn:walkthru-earth:property:acme_soil_volumetric_water_content"])
        assertNull(properties.of(null))
        // Matched exactly, so a missing trailing slash is another URI.
        assertNull(properties[temperature.trimEnd('/')])
    }

    @Test
    fun aNamedScaleResolvesInTheScaleRegistry() {
        val entry = properties[fine]!!
        val named = assertNotNull(entry.scaleId)
        assertEquals(RegistryScale.Named(named), entry.scale)
        val scale = scales[named]!!
        assertEquals(named, scale.id)
        assertEquals("P1D", scale.period)
        assertEquals(15.0, scale.guideline)
        assertEquals(6, scale.steps.size)
    }

    @Test
    fun anExplicitNullScaleStaysNone() {
        val entries = properties.entries.filter { it.scale == RegistryScale.None }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.uri == carbonDioxide }, "the registry gives CO2 no scale on purpose")
        entries.forEach { e ->
            assertNull(e.scaleId, e.uri)
            assertNull(scales[e.scaleId], e.uri)
        }
    }

    @Test
    fun aMissingScaleKeyIsUnsetNotNone() {
        assertEquals(RegistryScale.Unset, properties[temperature]!!.scale)
        assertNull(properties[temperature]!!.scaleId)
    }

    @Test
    fun everyNamedRegistryScaleExistsAndEveryScaleHasAPeriod() {
        properties.entries.mapNotNull { it.scaleId }.forEach { assertNotNull(scales[it], it) }
        assertTrue(scales.scales.isNotEmpty())
        scales.scales.values.forEach { assertNotNull(it.period, it.id) }
        assertNull(scales["no-such-scale"])
        assertNull(scales[null])
    }

    @Test
    fun everyEntryHasAnEnglishLabelAKnownGroupAndADefinition() {
        assertTrue(properties.entries.size > 10)
        properties.entries.forEach { e ->
            assertTrue(e.group.isKnown, e.uri)
            assertNotNull(e.definition.byLanguage[LocalizedText.ENGLISH], e.uri)
            assertTrue(e.uri.startsWith("http://") || e.uri.startsWith("https://") || e.uri.startsWith("urn:"), e.uri)
        }
    }

    @Test
    fun everyBundledTemplatePropertyIsInTheRegistry() {
        BundledCatalog.templates.forEach { t ->
            t.datastreams.mapNotNull { it.property }.forEach { p -> assertNotNull(properties.of(p), "${t.id} ${p.id}") }
        }
    }

    @Test
    fun aRegistryOfTheWrongTypeFails() {
        assertFailsWith<IllegalArgumentException> { PropertyRegistry.parse("""{"type": "OpenSensorScaleRegistry", "properties": {}}""") }
        assertFailsWith<IllegalArgumentException> { ScaleRegistry.parse("""{"type": "OpenSensorPropertyRegistry", "scales": {}}""") }
    }

    @Test
    fun anEntryWithoutAnEnglishLabelOrWithAScaleObjectFails() {
        val base = """"definition": {"en": "x"}, "group": "gas", "decimals": 0"""
        assertFailsWith<IllegalArgumentException> {
            PropertyRegistry.parse("""{"type": "OpenSensorPropertyRegistry", "properties": {"urn:x": {"label": {"ar": "س"}, $base}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            PropertyRegistry.parse("""{"type": "OpenSensorPropertyRegistry", "properties": {"urn:x": {"label": {"en": "x"}, $base, "scale": {}}}}""")
        }
        val ok = PropertyRegistry.parse("""{"type": "OpenSensorPropertyRegistry", "properties": {"urn:x": {"label": {"en": "x"}, $base, "scale": null}}}""")
        assertEquals(RegistryScale.None, ok["urn:x"]!!.scale)
    }

    @Test
    fun aScalePeriodMustBeAnIsoDuration() {
        val step = listOf(ScaleStep(null, LocalizedText.of("any")))
        assertEquals("PT1H", Scale("s", "https://example.org", step, period = "PT1H").period)
        assertEquals("P1D", Scale("s", "https://example.org", step, period = "P1D").period)
        assertFailsWith<IllegalArgumentException> { Scale("s", "https://example.org", step, period = "1 day") }
        assertFailsWith<IllegalArgumentException> { Scale("s", "https://example.org", step, period = "P") }
        assertFailsWith<IllegalArgumentException> { Scale("s", "https://example.org", step, period = "PT") }
    }
}
