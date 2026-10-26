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
    // The current digests, aq-parquet ada9514 (#100). CoreS3 v6.14 and Waveshare v2.8.
    private val cores3Dict = "4f80c26cde8d7ea78c2160225d98386009a8651d9a049b4bf68a75da96015e94"
    private val waveshareDict = "6f567b38fecc165f544626168931b3dc3d674482da87ed78cb0bec5bf3dc6ceb"
    private val unknownDict = "a".repeat(64)

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
        // The a017edf bytes carry the retired e5060fb7 digest. Its columns did not change with the new one.
        val reviewed = BoardTemplate.parse(CORES3_TEMPLATE)
        val bundled = assertNotNull(BundledCatalog.template(cores3Dict))
        reviewed.datastreams.forEach { column ->
            assertEquals(column, bundled.datastream(column.name), column.name)
        }
        reviewed.liveKeys.forEach { (column, key) -> assertEquals(key, bundled.liveKeys[column], column) }
    }

    @Test
    fun lookupWorksWithAndWithoutADesc() {
        val bundled = assertNotNull(BundledCatalog.template(cores3Dict))
        assertSame(bundled, BundledCatalog.template(cores3Dict, desc = DESC_B))
        val served = BoardTemplate.parse(BundledFiles.files.getValue("templates/cores3-telemetry-v5.json"), desc = DESC_A)
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
        val json = BundledFiles.files.getValue("templates/waveshare-sim7670g-telemetry-v5.json").replace(waveshareDict, unknownDict)
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
        val station = assertNotNull(BundledCatalog.station("s1", cores3Dict))
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
    fun aGeneratorTemplateWithCodeClassesATitleAndKeywordsParses() {
        // aq-parquet #85 adds classification:classes on code columns, a title and keywords, all in the v0.1.0 schema.
        val bundled = BundledFiles.files.getValue("templates/waveshare-sim7670g-telemetry-v5.json")
        val json = bundled
            .replace(
                "\"name\": \"battery_health_state\",",
                "\"name\": \"battery_health_state\", \"classification:classes\": [{\"value\": 0, \"name\": \"unknown\", \"nodata\": true}, {\"value\": 1, \"name\": \"good\", \"title\": \"Good\"}],",
            )
            .replace("\"title\": \"Waveshare ESP32-S3 SIM7670G with a Plantower PMS5003T\"", "\"title\": \"Waveshare board\"")
            .replace("\"low-cost sensor\"", "\"low-cost sensor\", \"LTE\"")
        assertTrue("classification:classes\": [{\"value\": 0, \"name\": \"unknown\"" in json)
        val template = BoardTemplate.parse(json)
        val reference = assertNotNull(BundledCatalog.template(waveshareDict))
        assertEquals("Waveshare board", template.title)
        assertEquals(listOf("air quality", "PM2.5", "low-cost sensor", "LTE"), template.keywords)
        val state = template.datastream("battery_health_state")!!
        assertEquals(listOf(0L, 1L), state.classes.map { it.value })
        assertTrue(state.classOf(0)!!.nodata)
        assertEquals(reference.datastreams.map { it.copy(classes = emptyList()) }, template.datastreams.map { it.copy(classes = emptyList()) })
        assertEquals(reference.datastream("pms_status")!!.classes, template.datastream("pms_status")!!.classes)
        assertEquals(reference.liveKeys, template.liveKeys)
    }

    @Test
    fun twoProfilesForOneDictAreRefused() {
        val profile = assertNotNull(BundledCatalog.profile(cores3Dict))
        assertEquals(mapOf(cores3Dict to profile), BundledCatalog.byDict(listOf(profile)))
        assertFailsWith<IllegalArgumentException> { BundledCatalog.byDict(listOf(profile, profile.copy())) }
    }
}
