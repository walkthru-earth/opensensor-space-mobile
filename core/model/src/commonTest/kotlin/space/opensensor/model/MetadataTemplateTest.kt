package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake `acme-soil-probe-v1` board parses with its display hints, its scales and primary and its
 * declared setup, and a template without them (the bundled ones today) parses with none.
 */
class MetadataTemplateTest {
    private val acme = BoardTemplate.parse(ACME_SOIL_PROBE_TEMPLATE, desc = ACME_DESC)
    private val moisture = acme.datastream("soil_moisture_pct")!!

    @Test
    fun theUnknownBoardParsesWithEveryColumnAndItsLiveKeys() {
        assertEquals("acme-soil-probe-v1", acme.id)
        assertEquals(TemplateKey(ACME_DICT, ACME_DESC), acme.key)
        assertEquals(
            listOf("device_id", "event_time_utc_us", "probe_status", "probe_flags", "soil_moisture_pct", "soil_temperature_c", "battery_mv"),
            acme.datastreams.map { it.name },
        )
        assertEquals("sm", moisture.liveKey)
        assertEquals("soil_moisture_pct", acme.columnForLiveKey("sm"))
        assertEquals(ValidityGate.Equals("probe_status", GateValue.Number(1.0)), moisture.validWhen)
        assertEquals("urn:walkthru-earth:property:acme_soil_volumetric_water_content", moisture.property?.id)
    }

    @Test
    fun columnTitlesAndDescriptionsParseInEveryLanguage() {
        assertEquals("Soil moisture", moisture.title)
        assertEquals(mapOf("ar" to "رطوبة التربة", "fr" to "Humidité du sol"), moisture.titleI18n)
        assertEquals("رطوبة التربة", moisture.titleIn("ar"))
        assertEquals("Humidité du sol", moisture.titleIn("fr"))
        assertEquals("Soil moisture", moisture.titleIn("en"))
        assertEquals("Soil moisture", moisture.titleIn("de"))
        assertEquals("Water in the soil as a share of its volume.", moisture.descriptionIn("de"))
        assertEquals("الماء في التربة كنسبة من حجمها.", moisture.descriptionIn("ar"))
    }

    @Test
    fun decimalsGroupAndScaleParseAsHints() {
        assertEquals(1, moisture.decimals)
        assertEquals(DisplayGroup.CLIMATE, moisture.group)
        assertEquals("acme-soil-dryness", moisture.scale)
        assertEquals(0, acme.datastream("battery_mv")!!.decimals)
        assertEquals(DisplayGroup.POWER, acme.datastream("battery_mv")!!.group)
        assertEquals(DisplayGroup.DEVICE, acme.datastream("device_id")!!.group)
    }

    @Test
    fun aColumnWithoutHintsKeepsThemEmpty() {
        val temperature = acme.datastream("soil_temperature_c")!!
        assertNull(temperature.title)
        assertTrue(temperature.titleI18n.isEmpty())
        assertNull(temperature.titleIn("ar"))
        assertNull(temperature.description)
        assertNull(temperature.decimals)
        assertNull(temperature.group)
        assertNull(temperature.scale)
        assertNull(acme.scaleOf(temperature))
        assertTrue(temperature.bitfields.isEmpty())
    }

    @Test
    fun theTemplateScaleParsesWithItsPeriod() {
        val scale = acme.scaleOf(moisture)!!
        assertEquals("acme-soil-dryness", scale.id)
        assertEquals("PT1H", scale.period)
        assertEquals("%", scale.unit)
        assertEquals(listOf(10.0, 40.0, null), scale.steps.map { it.upper })
        assertEquals("جاف", scale.steps[0].label.get("ar"))
        assertEquals("Moist", scale.steps[1].label.get("ar"))
        assertEquals("Wet", scale.stepOf(55.0)!!.label.get("en"))
        assertEquals(setOf("acme-soil-dryness"), acme.scales.keys)
    }

    @Test
    fun theTopLevelPrimaryNamesAColumn() {
        assertEquals("soil_moisture_pct", acme.primary)
    }

    @Test
    fun aPrimaryThatNamesNoColumnIsIgnored() {
        val text = ACME_SOIL_PROBE_TEMPLATE.replace("\"opensensor:primary\": \"soil_moisture_pct\"", "\"opensensor:primary\": \"no_such_column\"")
        assertNull(BoardTemplate.parse(text).primary)
    }

    @Test
    fun statusClassesKeepTheirTitlesAndNodata() {
        val status = acme.datastream("probe_status")!!
        assertTrue(status.classOf(0)!!.nodata)
        assertEquals(CodeClass(1, "valid", "2E7D32", title = "Reading"), status.classOf(1))
        assertEquals("The probe reads air, push it back in.", status.classOf(2)!!.description)
        assertNull(status.classOf(9))
    }

    @Test
    fun bitFieldsNameEachFieldOfABitmask() {
        val flags = acme.datastream("probe_flags")!!
        assertEquals("{bitmask}", flags.unit)
        assertEquals(listOf("probe-disconnected", "battery-level"), flags.bitfields.map { it.name })
        val (disconnected, battery) = flags.bitfields
        assertEquals("The probe cable is unplugged.", disconnected.description)
        // 0b110001, bit 0 set and bits 4 and 5 hold 3.
        val raw = 0x31L
        assertEquals("set", disconnected.classIn(raw)!!.name)
        assertEquals(3, battery.valueIn(raw))
        assertEquals("full", battery.classIn(raw)!!.name)
        assertEquals("empty", battery.classIn(0x0FL)!!.name)
        assertEquals("ok", disconnected.classIn(0x30L)!!.name)
    }

    @Test
    fun aBitFieldOutsideSixtyFourBitsFails() {
        assertFailsWith<IllegalArgumentException> { BitField(offset = 60, length = 8, classes = emptyList()) }
        assertFailsWith<IllegalArgumentException> { BitField(offset = 0, length = 0, classes = emptyList()) }
        assertFailsWith<IllegalArgumentException> { BitField(offset = Int.MAX_VALUE, length = 1, classes = emptyList()) }
        assertFailsWith<IllegalArgumentException> { BitField(offset = -1, length = 1, classes = emptyList()) }
        assertEquals(-1L, BitField(offset = 0, length = 64, classes = emptyList()).valueIn(-1L))
    }

    @Test
    fun decimalsAboveNineFail() {
        val text = ACME_SOIL_PROBE_TEMPLATE.replace("\"opensensor:decimals\": 1,", "\"opensensor:decimals\": 12,")
        assertFailsWith<IllegalArgumentException> { BoardTemplate.parse(text) }
    }

    @Test
    fun anUnknownGroupParsesAndKeepsItsId() {
        val text = ACME_SOIL_PROBE_TEMPLATE.replace("\"opensensor:group\": \"climate\"", "\"opensensor:group\": \"soil\"")
        val group = BoardTemplate.parse(text).datastream("soil_moisture_pct")!!.group!!
        assertEquals("soil", group.id)
        assertFalse(group.isKnown)
        assertTrue(DisplayGroup.CLIMATE.isKnown)
    }

    @Test
    fun setupStepsKeepTheirOrderAndUnknownIds() {
        val steps = acme.setup.setup
        assertEquals(listOf("name", "location", "wifi", "probe", "acme-mystery"), steps.map { it.id.id })
        assertEquals(listOf(false, true, false, true, true), steps.map { it.required })
        assertEquals(listOf(true, true, true, false, false), steps.map { it.id.isKnown })
        assertEquals(listOf("probe.depth_cm", "probe.mode", "probe.not_declared"), steps[3].keys)
        assertTrue(steps[4].keys.isEmpty())
    }

    @Test
    fun aStepKeyNoConfigKeyDeclaresIsSkippedOnLookup() {
        val probe = acme.setup.setup.single { it.id == StepId("probe") }
        assertEquals(listOf("probe.depth_cm", "probe.mode"), acme.setup.keysOf(probe.keys).map { it.key })
    }

    @Test
    fun settingsSectionsDropAnEntryWithoutAnId() {
        val sections = acme.setup.settings
        assertEquals(listOf(StepId.LOCATION, StepId("probe")), sections.map { it.id })
        assertEquals(listOf("probe.depth_cm", "probe.mode", "probe.calibrate"), sections[1].keys)
    }

    @Test
    fun configKeysParseTyped() {
        val keys = acme.setup.configKeys
        assertEquals(setOf("probe.depth_cm", "probe.mode", "probe.calibrate", "probe.future"), keys.keys)
        val depth = keys.getValue("probe.depth_cm")
        assertEquals(ConfigKeyType.INTEGER, depth.type)
        assertEquals(ConfigValues.Range(5.0, 60.0), depth.values)
        assertEquals(ConfigApplies.NEXT_SAMPLE, depth.applies)
        assertEquals(7, depth.page)
        assertEquals("Probe depth", depth.label!!.get("en"))
        assertEquals("عمق المجس", depth.label!!.get("ar"))
        val mode = keys.getValue("probe.mode")
        assertEquals(ConfigKeyType.ENUM, mode.type)
        assertEquals(ConfigValues.Choices(listOf("fast", "slow")), mode.values)
        assertEquals(ConfigApplies.NEXT_BOOT, mode.applies)
        val calibrate = keys.getValue("probe.calibrate")
        assertEquals(ConfigKeyType.BOOLEAN, calibrate.type)
        assertEquals(ConfigApplies.ACTION, calibrate.applies)
        assertNull(calibrate.values)
    }

    @Test
    fun anUnknownKeyTypeAndAppliesStayAsDataAndBadFieldsDrop() {
        val future = acme.setup.configKeys.getValue("probe.future")
        assertEquals("acme-color", future.type.id)
        assertFalse(future.type.isKnown)
        assertEquals("on full moon", future.applies!!.id)
        assertFalse(future.applies!!.isKnown)
        assertNull(future.values)
        assertNull(future.page)
        assertNull(future.label)
    }

    @Test
    fun aTemplateWithoutHintsOrSetupParsesWithNone() {
        val cores3 = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
        assertNull(cores3.primary)
        assertTrue(cores3.scales.isEmpty())
        assertTrue(cores3.setup.isEmpty)
        cores3.datastreams.forEach { ds ->
            assertNull(ds.title, ds.name)
            assertNull(ds.decimals, ds.name)
            assertNull(ds.group, ds.name)
            assertTrue(ds.bitfields.isEmpty(), ds.name)
        }
    }

    @Test
    fun malformedSetupBlocksNeverCostTheColumns() {
        val text = template(
            columns = """{"name": "event_time_utc_us", "type": "int64", "roles": ["coordinate"]}""",
        ).replace(
            "\"live_keys\": {}",
            "\"live_keys\": {}, \"setup\": [7, null, {\"id\": 3}], \"settings\": [[]], \"config_keys\": {\"a\": 1, \"b\": {\"type\": 2}}",
        )
        val parsed = BoardTemplate.parse(text)
        assertEquals(listOf("event_time_utc_us"), parsed.datastreams.map { it.name })
        assertTrue(parsed.setup.isEmpty)
        val wrongShapes = text.replace("\"setup\": [7, null, {\"id\": 3}]", "\"setup\": {\"id\": \"name\"}")
            .replace("\"settings\": [[]]", "\"settings\": \"none\"")
            .replace("\"config_keys\": {\"a\": 1, \"b\": {\"type\": 2}}", "\"config_keys\": [1]")
        assertTrue("\"config_keys\": [1]" in wrongShapes)
        assertTrue(BoardTemplate.parse(wrongShapes).setup.isEmpty)
    }

    @Test
    fun everyBundledTemplateParsesWithItsStatusClasses() {
        val crowpanel = BundledCatalog.templates.single { it.id == "crowpanel-epaper-telemetry-v1" }
        val status = crowpanel.datastream("sen6x_status")!!
        assertEquals(listOf("missing", "warming", "stale", "sensor-error", "valid"), status.classes.map { it.name })
        BundledCatalog.templates.forEach { t ->
            assertTrue(t.primary == null || t.datastream(t.primary!!) != null, t.id)
            t.datastreams.forEach { ds -> assertTrue(ds.scale == null || ds.scale in t.scales, "${t.id} ${ds.name}") }
        }
    }
}
