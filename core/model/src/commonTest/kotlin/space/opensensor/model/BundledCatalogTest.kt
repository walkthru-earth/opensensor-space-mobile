package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BundledCatalogTest {
    private val waveshareDict = "67688d0fd2e86053c1c66b961a1446ad3c225ee71823e0fadb771b645c7183ff"
    private val unknownDict = "a".repeat(64)

    @Test
    fun bundlesOnlyTheCurrentDigestOfEachBoard() {
        assertEquals(setOf(CORES3_DICT, waveshareDict), BundledCatalog.templates.map { it.dict }.toSet())
        assertEquals(2, BundledCatalog.templates.size)
        assertEquals(setOf(CORES3_DICT, waveshareDict), BundledCatalog.profiles.map { it.dict }.toSet())
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
        val reviewed = BoardTemplate.parse(CORES3_TEMPLATE)
        val bundled = assertNotNull(BundledCatalog.template(CORES3_DICT))
        assertEquals(reviewed.dict, bundled.dict)
        reviewed.datastreams.forEach { column ->
            assertEquals(column, bundled.datastream(column.name), column.name)
        }
        reviewed.liveKeys.forEach { (column, key) -> assertEquals(key, bundled.liveKeys[column], column) }
    }

    @Test
    fun lookupWorksWithAndWithoutADesc() {
        val bundled = assertNotNull(BundledCatalog.template(CORES3_DICT))
        assertSame(bundled, BundledCatalog.template(CORES3_DICT, desc = DESC_B))
        val served = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
        assertSame(served, BundledCatalog.index(served = listOf(served)).resolve(CORES3_DICT, DESC_A))
        assertSame(bundled, BundledCatalog.index(served = listOf(served)).resolve(CORES3_DICT))
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
        val live = assertNotNull(BundledCatalog.live(CORES3_DICT))
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
        val station = assertNotNull(BundledCatalog.station("s1", CORES3_DICT))
        val battery = station.datastream("battery_percent")!!
        assertNull(battery.datastream.property)
        assertEquals("Battery", battery.label("en"))
        assertEquals("%", battery.displayUnit)
    }

    @Test
    fun anAirTemperatureWinsOverABoardTemperatureThatComesFirst() {
        // CoreS3 lists imu_temperature_c (auxiliaryInformation) before ambient_temperature_c.
        val template = assertNotNull(BundledCatalog.template(CORES3_DICT))
        val live = LiveColumns.of(template, AppProfile.derive(template))
        assertEquals("t", live.temperature?.liveKey)
    }

    @Test
    fun aHiddenColumnIsDroppedFromPrimaryEvenWhenTheProfileListsIt() {
        val template = assertNotNull(BundledCatalog.template(CORES3_DICT))
        val profile = AppProfile.parse(
            """{"dict": "$CORES3_DICT", "family": "air", "primary": ["pm25_atmospheric_ug_m3", "ambient_temperature_c"], "hidden": ["ambient_temperature_c"]}""",
        )
        val station = Station.join("s1", template, profile)
        assertEquals(listOf("pm25_atmospheric_ug_m3"), station.primary.map { it.name })
    }
}
