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
    // The current digests and descs, aq-parquet a09f5ae docs/shared/templates (#86).
    private val cores3Dict = "4f80c26cde8d7ea78c2160225d98386009a8651d9a049b4bf68a75da96015e94"
    private val waveshareDict = "6f567b38fecc165f544626168931b3dc3d674482da87ed78cb0bec5bf3dc6ceb"
    private val cores3Desc = "1b7d969abb670579cd1f34f0bd7e224f66b120ed178fbb2fcf024e14a46c3f08"
    private val waveshareDesc = "e6b32af089b5c0b8389999a09b4a81ffe36d15f908a2002765025f78853b7d32"
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
        // of 1 now sits in the column, so the firmware drops its unit label. The one changed fact is
        // the board temperature range, widened to the BMI270 rating of -40 to 85.
        val reviewed = BoardTemplate.parse(CORES3_TEMPLATE)
        val bundled = assertNotNull(BundledCatalog.template(cores3Dict))
        reviewed.datastreams.forEach { column ->
            val b = assertNotNull(bundled.datastream(column.name), column.name)
            val filled = column.copy(
                unit = column.unit ?: b.unit,
                property = column.property ?: b.property,
                aggregate = column.aggregate ?: b.aggregate,
                classes = column.classes.ifEmpty { b.classes },
                unitLabel = column.unitLabel?.takeIf { it != b.unit },
                plausibleRange = if (column.name == "imu_temperature_c") PlausibleRange(-40.0, 85.0) else column.plausibleRange,
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
        val station = assertNotNull(BundledCatalog.station("s1", waveshareDict))
        assertFalse(station.profile.isDerived)
        val pm25 = station.datastream("pm25_atmospheric_ug_m3")!!
        assertEquals("PM2.5 raw", pm25.label("en"))
        assertEquals("PM2.5 خام", pm25.label("ar"))
        assertEquals("µg/m³", pm25.displayUnit)
        assertEquals("pm25", pm25.scaleId)
        assertEquals(PlausibleRange(0.0, 1000.0), pm25.datastream.plausibleRange)
        assertNull(BundledCatalog.station("s1", unknownDict))
    }

    @Test
    fun anUnknownDigestShowsRawColumnNamesAndRawUnits() {
        val json = BundledFiles.files.getValue(waveshareFile).replace(waveshareDict, unknownDict)
        val served = BoardTemplate.parse(json, desc = DESC_B)
        val station = assertNotNull(BundledCatalog.station("s1", unknownDict, desc = DESC_B, served = listOf(served)))
        assertTrue(station.profile.isDerived)
        station.datastreams.forEach { ds ->
            assertEquals(ds.name, ds.label("en"), ds.name)
            assertEquals(ds.name, ds.label("ar"), ds.name)
            assertEquals(ds.datastream.unit, ds.displayUnit, ds.name)
            assertNull(ds.scaleId, ds.name)
        }
        assertEquals("ug/m3", station.datastream("pm25_atmospheric_ug_m3")!!.displayUnit)
        assertEquals("Cel", station.datastream("ambient_temperature_c")!!.displayUnit)
        // Nothing is hidden, so LIVE shows every key the template maps.
        assertEquals(served.liveKeys.values.toSet(), station.live.keys)
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
        val station = Station.join("s1", BoardTemplate.parse(json), BundledCatalog.profile(cores3Dict))
        val battery = station.datastream("battery_percent")!!
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
        val station = Station.join("s1", template, profile)
        assertEquals(listOf("pm25_atmospheric_ug_m3"), station.primary.map { it.name })
    }

    @Test
    fun theFirmwareTemplateCarriesCodeClassesATitleAndKeywords() {
        // aq-parquet #85 generates classification:classes on code columns, a title and keywords.
        val template = assertNotNull(BundledCatalog.template(waveshareDict))
        assertEquals("Waveshare ESP32-S3-SIM7670G-4G V2.0 with Plantower PMS5003T and Analog Devices MAX17048", template.title)
        assertTrue("PM2.5" in template.keywords)
        val state = template.datastream("battery_health_state")!!
        assertTrue(state.classOf(0)!!.nodata)
        assertEquals("model-mismatch", template.datastream("pms_status")!!.classes.last().name)
    }

    @Test
    fun bundledTemplatesCarryTheDescOfTheIndex() {
        assertEquals(cores3Desc, BundledCatalog.template(cores3Dict)?.key?.desc)
        assertEquals(waveshareDesc, BundledCatalog.template(waveshareDict)?.key?.desc)
        assertEquals(listOf("cores3-telemetry-v5", "waveshare-sim7670g-telemetry-v5"), BundledCatalog.templates.map { it.schema })
    }

    @Test
    fun theIndexMustListEveryTemplateWithItsDictAndSchema() {
        val files = BundledFiles.files
        val stray = files + ("templates/m5stack-cores3/$unknownDict.json" to files.getValue(cores3File))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(stray) }
        val missing = files - cores3File
        assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(missing) }
        val wrongDict = files + (cores3File to files.getValue(waveshareFile))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.templatesOf(wrongDict) }
        assertEquals(BundledCatalog.templates, BundledCatalog.templatesOf(files))
    }

    @Test
    fun twoProfilesForOneDictAreRefused() {
        val profile = assertNotNull(BundledCatalog.profile(cores3Dict))
        assertEquals(mapOf(cores3Dict to profile), BundledCatalog.byDict(listOf(profile)))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.byDict(listOf(profile, profile.copy())) }
    }
}
