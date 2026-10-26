package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BundledCatalogTest {
    private val indexFile = "templates/index.json"

    // The current digests, opensensor-space-firmware docs/shared/templates (#86). A desc move keeps them.
    private val cores3Dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"
    private val waveshareDict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7"
    private val unknownDict = "a".repeat(64)
    private val cores3File = "templates/m5stack-cores3/$cores3Dict.json"
    private val waveshareFile = "templates/waveshare-esp32-s3-sim7670g/$waveshareDict.json"

    @Test
    fun bundlesOnlyTheCurrentDigestOfEachBoard() {
        assertEquals(setOf(cores3Dict, waveshareDict), BundledCatalog.templates.map { it.dict }.toSet())
        assertEquals(2, BundledCatalog.templates.size)
        assertEquals(setOf(cores3Dict, waveshareDict), BundledCatalog.profiles.map { it.dict }.toSet())
        BundledCatalog.profiles.forEach { assertFalse(it.isDerived, it.dict) }
    }

    @Test
    fun everyBundledProfileNamesOnlyColumnsOfItsTemplate() {
        BundledCatalog.profiles.forEach { profile ->
            val template = assertNotNull(BundledCatalog.template(profile.dict), profile.dict)
            val named = profile.primary + profile.hidden + profile.labels.keys + profile.displayUnit.keys + profile.scales.keys + profile.displayStep.keys
            named.forEach { assertNotNull(template.datastream(it), "${template.id} has no column $it") }
        }
    }

    @Test
    fun theBundledCores3TemplateKeepsTheReviewedA017edfColumns() {
        // The a017edf bytes carry the retired e5060fb7 digest. The firmware bytes keep every fact it
        // reviewed and only add what it left out, a unit, property, aggregate or code classes. A unit
        // of 1 now sits in the column, so the firmware drops its unit label. A plausible range may only
        // widen, as the board temperature range did to the BMI270 rating of -40 to 85. Dictionary v7
        // replaces the two H3 centre columns with location_source and geometry, and the sensor board
        // follows the schema name.
        val reviewed = BoardTemplate.parse(CORES3_TEMPLATE)
        val bundled = assertNotNull(BundledCatalog.template(cores3Dict))
        val replaced = setOf("h3_center_lat_e7", "h3_center_lon_e7")
        reviewed.datastreams.filterNot { it.name in replaced }.forEach { column ->
            val b = assertNotNull(bundled.datastream(column.name), column.name)
            val filled = column.copy(
                sensor = column.sensor?.copy(board = b.sensor?.board),
                fieldId = b.fieldId,
                unit = column.unit ?: b.unit,
                property = column.property ?: b.property,
                aggregate = column.aggregate ?: b.aggregate,
                classes = column.classes.ifEmpty { b.classes },
                unitLabel = column.unitLabel?.takeIf { it != b.unit },
                plausibleRange = column.plausibleRange?.let { r -> b.plausibleRange?.takeIf { it.min <= r.min && it.max >= r.max } ?: r },
            )
            assertEquals(filled, b, column.name)
        }
        reviewed.liveKeys.forEach { (column, key) -> assertEquals(key, bundled.liveKeys[column], column) }
    }

    @Test
    fun lookupWorksWithAndWithoutADesc() {
        val bundled = assertNotNull(BundledCatalog.template(cores3Dict))
        assertSame(bundled, BundledCatalog.template(cores3Dict, desc = DESC_B))
        val served = BoardTemplate.parse(BundledFiles.files.getValue(cores3File), desc = DESC_A)
        assertSame(served, BundledCatalog.index(served = listOf(served)).resolve(cores3Dict, DESC_A))
        assertSame(bundled, BundledCatalog.index(served = listOf(served)).resolve(cores3Dict))
        assertNull(BundledCatalog.template(unknownDict))
        assertNull(BundledCatalog.profile(unknownDict))
    }

    @Test
    fun theBundledJoinUsesTheBundledProfile() {
        val thing = assertNotNull(BundledCatalog.thing("s1", waveshareDict))
        assertFalse(thing.profile.isDerived)
        val pm25 = thing.datastream("pm25_atmospheric_ug_m3")!!
        assertEquals("PM2.5 raw", pm25.label("en"))
        assertEquals("PM2.5 خام", pm25.label("ar"))
        assertEquals("µg/m³", pm25.displayUnit)
        assertEquals("pm25", pm25.scaleId)
        assertEquals(PlausibleRange(0.0, 1000.0), pm25.datastream.plausibleRange)
        assertNull(BundledCatalog.thing("s1", unknownDict))
    }

    @Test
    fun anUnknownDigestShowsRawColumnNamesAndRawUnits() {
        val json = BundledFiles.files.getValue(waveshareFile).replace(waveshareDict, unknownDict)
        val served = BoardTemplate.parse(json, desc = DESC_B)
        val thing = assertNotNull(BundledCatalog.thing("s1", unknownDict, desc = DESC_B, served = listOf(served)))
        assertTrue(thing.profile.isDerived)
        thing.datastreams.forEach { ds ->
            assertEquals(ds.name, ds.label("en"), ds.name)
            assertEquals(ds.name, ds.label("ar"), ds.name)
            assertEquals(ds.datastream.unit, ds.displayUnit, ds.name)
            assertNull(ds.scaleId, ds.name)
        }
        assertEquals("ug/m3", thing.datastream("pm25_atmospheric_ug_m3")!!.displayUnit)
        assertEquals("Cel", thing.datastream("ambient_temperature_c")!!.displayUnit)
        // Nothing is hidden, so LIVE shows every key the template maps.
        assertEquals(served.liveKeys.values.toSet(), thing.live.keys)
    }

    @Test
    fun theCores3LiveViewHidesTheAlwaysNullAmbientColumns() {
        val live = assertNotNull(BundledCatalog.live(cores3Dict))
        assertFalse("t" in live.keys)
        assertFalse("rh" in live.keys)
        assertTrue("imu_t" in live.keys)
        assertEquals("imu_t", live.temperature?.liveKey)
        assertNull(live.humidity)
    }

    @Test
    fun theWaveshareLiveViewReadsAmbientTemperatureAndHumidity() {
        val live = assertNotNull(BundledCatalog.live(waveshareDict))
        assertEquals("t", live.temperature?.liveKey)
        assertEquals("rh", live.humidity?.liveKey)
        assertTrue("bstate" in live.keys)
        assertNull(BundledCatalog.live(unknownDict))
    }

    @Test
    fun propertyIsOptionalOnEveryColumn() {
        // Every published column names a property today. A template that drops one still joins.
        val json = BundledFiles.files.getValue(cores3File).replace("\"opensensor:property\":\"urn:walkthru-earth:property:battery_state_of_charge\",", "")
        val thing = Thing.join("s1", BoardTemplate.parse(json), BundledCatalog.profile(cores3Dict))
        val battery = thing.datastream("battery_percent")!!
        assertNull(battery.datastream.property)
        assertEquals("Battery", battery.label("en"))
        assertEquals("%", battery.displayUnit)
    }

    @Test
    fun anAirTemperatureWinsOverABoardTemperatureThatComesFirst() {
        // CoreS3 lists imu_temperature_c (auxiliaryInformation) before ambient_temperature_c.
        val template = assertNotNull(BundledCatalog.template(cores3Dict))
        val live = LiveColumns.of(template, AppProfile.derive(template))
        assertEquals("t", live.temperature?.liveKey)
    }

    @Test
    fun aHiddenColumnIsDroppedFromPrimaryEvenWhenTheProfileListsIt() {
        val template = assertNotNull(BundledCatalog.template(cores3Dict))
        val profile = AppProfile.parse(
            """{"dict": "$cores3Dict", "family": "air", "primary": ["pm25_atmospheric_ug_m3", "ambient_temperature_c"], "hidden": ["ambient_temperature_c"]}""",
        )
        val thing = Thing.join("s1", template, profile)
        assertEquals(listOf("pm25_atmospheric_ug_m3"), thing.primary.map { it.name })
    }

    @Test
    fun theFirmwareTemplateCarriesCodeClassesATitleAndKeywords() {
        // opensensor-space-firmware#85 generates classification:classes on code columns, a title and keywords.
        val template = assertNotNull(BundledCatalog.template(waveshareDict))
        assertTrue(template.title!!.startsWith("Waveshare"))
        assertTrue("PM2.5" in template.keywords)
        val state = template.datastream("battery_health_state")!!
        assertTrue(state.classOf(0)!!.nodata)
        assertTrue(template.datastream("pms_status")!!.classes.isNotEmpty())
    }

    @Test
    fun bundledTemplatesCarryTheDescOfTheIndex() {
        val index = ModelJson.parseTemplateIndex(BundledFiles.files.getValue("templates/index.json")).associate { it.dict to it.desc }
        assertEquals(index.getValue(cores3Dict), BundledCatalog.template(cores3Dict)?.key?.desc)
        assertEquals(index.getValue(waveshareDict), BundledCatalog.template(waveshareDict)?.key?.desc)
        assertEquals(listOf("cores3-telemetry-v6", "waveshare-sim7670g-telemetry-v6"), BundledCatalog.templates.map { it.schema })
    }

    @Test
    fun theIndexMustListEveryTemplateWithItsDictAndSchema() {
        val files = BundledFiles.files
        val stray = files + ("templates/m5stack-cores3/$unknownDict.json" to files.getValue(cores3File))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(stray) }
        val missing = files - cores3File
        assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(missing) }
        assertEquals(BundledCatalog.templates, BundledCatalog.templatesOf(files))
    }

    @Test
    fun theIndexDictMustMatchTheTemplateDict() {
        val index = BundledFiles.files.getValue(indexFile)
        val moved = index.replace("\"$cores3Dict\": {", "\"$unknownDict\": {")
        assertTrue(moved != index)
        val error = assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(BundledFiles.files + (indexFile to moved)) }
        assertTrue("dict" in error.message.orEmpty(), error.message)
    }

    @Test
    fun theIndexSchemaMustMatchTheTemplateSchema() {
        val index = BundledFiles.files.getValue(indexFile)
        val renamed = index.replace("\"schema\": \"cores3-telemetry-v6\"", "\"schema\": \"cores3-telemetry-v99\"")
        assertTrue(renamed != index)
        val error = assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(BundledFiles.files + (indexFile to renamed)) }
        assertTrue("schema" in error.message.orEmpty(), error.message)
    }

    @Test
    fun theIndexDescMustBeTheDigestOfTheTemplateBytes() {
        val digests = BundledFiles.sha256 + (cores3File to unknownDict)
        val error = assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(BundledFiles.files, digests) }
        assertTrue("desc" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aRealFirmwareDescResolvesWithoutADict() {
        // The desc firmware writes in template.sha256 for the CoreS3 v5 template.
        val desc = "1c9511bd6836876bf90bbdbe3224b00bd5d176e0fdd5293d5ee6dd73ef6ea45a"
        val template = assertNotNull(BundledCatalog.templateByDesc(desc))
        assertEquals(cores3Dict, template.dict)
        assertEquals(desc, template.key.desc)
    }

    @Test
    fun twoProfilesForOneDictAreRefused() {
        val profile = assertNotNull(BundledCatalog.profile(cores3Dict))
        assertEquals(mapOf(cores3Dict to profile), BundledCatalog.byDict(listOf(profile)))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.byDict(listOf(profile, profile.copy())) }
    }
}
