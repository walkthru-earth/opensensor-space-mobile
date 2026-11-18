package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AppProfile.derive] on a board the app has never seen, with no app change. */
class AppProfileTest {
    private val acme = BoardTemplate.parse(ACME_SOIL_PROBE_TEMPLATE, desc = ACME_DESC)
    private val thing = Thing.join("acme-1", acme)

    @Test
    fun labelsComeFromTheColumnTitleByLanguageElseTheNameWithSpaces() {
        val moisture = thing.datastream("soil_moisture_pct")!!
        assertEquals("Soil moisture", moisture.label("en"))
        assertEquals("رطوبة التربة", moisture.label("ar"))
        assertEquals("Humidité du sol", moisture.label("fr"))
        assertEquals("Soil moisture", moisture.label("de"))
        // No title and a property the registry lacks.
        assertEquals("soil temperature c", thing.datastream("soil_temperature_c")!!.label("en"))
        assertEquals("soil temperature c", thing.datastream("soil_temperature_c")!!.label("ar"))
    }

    @Test
    fun decimalsComeFromTheColumnElseTheType() {
        assertEquals(1, thing.datastream("soil_moisture_pct")!!.decimals)
        assertEquals(2, thing.datastream("soil_temperature_c")!!.decimals)
        assertEquals(0, thing.datastream("battery_mv")!!.decimals)
        assertEquals(0, thing.datastream("probe_status")!!.decimals)
    }

    @Test
    fun unitsShowTheirUcumSymbol() {
        assertEquals("%", thing.datastream("soil_moisture_pct")!!.displayUnit)
        assertEquals("°C", thing.datastream("soil_temperature_c")!!.displayUnit)
        assertEquals("mV", thing.datastream("battery_mv")!!.displayUnit)
        assertEquals("", thing.datastream("probe_flags")!!.displayUnit)
    }

    @Test
    fun theTemplatePrimaryWinsAndOnlyItsOwnScaleApplies() {
        assertEquals("soil_moisture_pct", thing.primary?.name)
        assertEquals(Family.GENERIC, thing.profile.family)
        assertNull(thing.profile.hero)
        assertEquals("acme-soil-dryness", thing.datastream("soil_moisture_pct")!!.scale?.id)
        assertEquals(setOf("soil_moisture_pct"), thing.profile.scales.keys)
    }

    @Test
    fun aColumnScaleWinsOverTheRegistry() {
        val property = "\"opensensor:property\": \"http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/\""
        val pm25 = """{"name": "pm25", "type": "float", "unit": "ug/m3", "roles": ["physicalMeasurement"], $property, "opensensor:scale": "local"}"""
        val scales = """"opensensor:scales": {"local": {"source": "https://example.org", "unit": "ug/m3",
            "steps": [{"upper": 10, "label": "Low"}, {"upper": null, "label": "High"}]}},"""
        val local = BoardTemplate.parse(template(pm25).replaceFirst("\"item_assets\"", "$scales \"item_assets\""))
        assertEquals("local", AppProfile.derive(local).scales["pm25"]?.id)
        // A column scale nothing defines is neutral, never the registry scale.
        assertNull(AppProfile.derive(BoardTemplate.parse(template(pm25))).scales["pm25"])
        // Without a column scale the registry scale applies.
        val bare = BoardTemplate.parse(template(pm25.replace(", \"opensensor:scale\": \"local\"", "")))
        assertEquals("who-2021-pm25", AppProfile.derive(bare).scales["pm25"]?.id)
    }
}
