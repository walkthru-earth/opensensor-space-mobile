package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StationTest {
    private val cores3 = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
    private val profile = AppProfile.parse(CORES3_PROFILE)
    private val collection = StationCollection.parse(CORES3_COLLECTION)

    @Test
    fun parsesTheAppProfile() {
        assertEquals(CORES3_DICT, profile.dict)
        assertEquals(Family("air"), profile.family)
        assertEquals("mood", profile.hero)
        assertEquals("جسيمات 2.5", profile.labels["pm25_atmospheric_ug_m3"]?.get("ar"))
        assertEquals(1.0, profile.displayStep["pm25_atmospheric_ug_m3"])
        assertFalse(profile.isDerived)
    }

    @Test
    fun parsesTheStationCollection() {
        assertEquals("station-3f2b9c1e", collection.id)
        assertEquals("CC-BY-4.0", collection.license)
        assertEquals(30.0, collection.sampleIntervalS)
        assertEquals(CORES3_DICT, collection.dict)
        assertEquals(DESC_A, collection.desc)
        assertEquals("PM2.5", collection.columnTitles["pm25_atmospheric_ug_m3"])
        assertEquals("who-2021-pm25", collection.columnScales["pm25_atmospheric_ug_m3"])
        val scale = collection.scales.getValue("who-2021-pm25")
        assertEquals(5.0, scale.guideline)
        assertEquals("ug/m3", scale.unit)
        assertEquals("الحد الإرشادي", scale.steps[0].label.get("ar"))
        assertEquals("Interim target 4", scale.steps[1].label.get("ar"))
        assertNull(scale.steps[2].upper)
        assertEquals("C62828", scale.stepOf(400.0)?.colorHint)
    }

    @Test
    fun joinUsesTheBundledProfile() {
        val station = Station.join("3f2b9c1e", cores3, profile, collection, deviceId = 7, h3Cell = "887576970bfffff")
        assertSame(profile, station.profile)
        assertEquals(CORES3_DICT, station.dict)
        // Primary columns the template lacks are dropped, the order stays.
        assertEquals(listOf("pm25_atmospheric_ug_m3", "pm10_atmospheric_ug_m3"), station.primary.map { it.name })
        val pm25 = station.datastream("pm25_atmospheric_ug_m3")!!
        assertEquals("جسيمات 2.5", pm25.label("ar"))
        assertEquals("PM2.5", pm25.title)
        assertEquals("who-2021-pm25", pm25.scale?.id)
        assertEquals(1.0, pm25.displayStep)
        assertEquals(0.5, station.datastream("imu_temperature_c")!!.displayStep)
        // No profile label, so the station title, then the column name.
        assertEquals("Particle sensor status", station.datastream("pms_status")!!.label("en"))
        assertEquals("accel_z_g", station.datastream("accel_z_g")!!.label("en"))
        assertNull(station.datastream("pm10_atmospheric_ug_m3")!!.scale)
        assertEquals("pm25_atmospheric_ug_m3", station.datastreamForLiveKey("pm25")?.name)
        assertNull(station.datastreamForLiveKey("zzz"))
    }

    @Test
    fun derivedProfileWhenNoneIsBundled() {
        val station = Station.join("3f2b9c1e", cores3)
        assertTrue(station.profile.isDerived)
        assertEquals(Family.GENERIC, station.profile.family)
        assertNull(station.profile.hero)
        // The first physicalMeasurement column of the template.
        assertEquals(listOf("pm1_atmospheric_ug_m3"), station.primary.map { it.name })
        assertEquals("pm1_atmospheric_ug_m3", station.datastream("pm1_atmospheric_ug_m3")!!.label("ar"))
    }

    @Test
    fun derivedProfileLabelsComeFromStationTitles() {
        val station = Station.join("3f2b9c1e", cores3, collection = collection)
        assertTrue(station.profile.isDerived)
        assertEquals("PM2.5", station.datastream("pm25_atmospheric_ug_m3")!!.label("en"))
        assertEquals("pm10_atmospheric_ug_m3", station.datastream("pm10_atmospheric_ug_m3")!!.label("en"))
    }

    @Test
    fun derivedProfileSkipsNonMeasurementColumns() {
        val t = BoardTemplate.parse(
            template(
                """
                {"name": "status", "type": "int32", "roles": ["qualityInformation"]},
                {"name": "battery", "type": "int32", "roles": ["auxiliaryInformation"]},
                {"name": "soil_moisture_pct", "type": "float", "unit": "%", "roles": ["physicalMeasurement"]}
                """,
            ),
        )
        assertEquals(listOf("soil_moisture_pct"), AppProfile.derive(t).primary)
        val none = BoardTemplate.parse(template("""{"name": "battery", "type": "int32", "roles": ["auxiliaryInformation"]}"""))
        assertEquals(emptyList(), AppProfile.derive(none).primary)
    }

    @Test
    fun profileForAnotherDictionaryIsNotUsed() {
        val other = BoardTemplate.parse(template("""{"name": "pm25_atmospheric_ug_m3", "type": "int32", "roles": ["physicalMeasurement"]}"""))
        val station = Station.join("s", other, profile)
        assertTrue(station.profile.isDerived)
        assertEquals(OTHER_DICT, station.profile.dict)
    }

    @Test
    fun profileWithNoKnownPrimaryColumnFallsBackToDerived() {
        val bare = profile.copy(primary = listOf("no_such_column"))
        assertTrue(Station.join("s", cores3, bare).profile.isDerived)
    }

    @Test
    fun runtimeSampleIntervalWins() {
        assertEquals(10.0, Station.join("s", cores3).sampleIntervalS)
        assertEquals(30.0, Station.join("s", cores3, collection = collection).sampleIntervalS)
        assertEquals(60.0, Station.join("s", cores3, collection = collection, runtimeSampleIntervalS = 60.0).sampleIntervalS)
        assertEquals(30.0, Station.join("s", cores3, collection = collection, runtimeSampleIntervalS = 0.0).sampleIntervalS)
    }

    @Test
    fun fileFooterSampleIntervalWinsForThatFile() {
        val station = Station.join("s", cores3, collection = collection, runtimeSampleIntervalS = 60.0)
        assertEquals(5.0, station.sampleIntervalFor(footerSampleIntervalMs = 5_000))
        assertEquals(0.5, station.sampleIntervalFor(footerSampleIntervalMs = 500))
        assertEquals(60.0, station.sampleIntervalFor(footerSampleIntervalMs = 0))
        assertEquals(60.0, station.sampleIntervalFor(footerSampleIntervalMs = null))
    }

    @Test
    fun stationKeepsEveryDatastreamWhateverItsRole() {
        val station = Station.join("s", cores3)
        assertEquals(cores3.datastreams.map { it.name }, station.datastreams.map { it.name })
        assertTrue(station.datastream("battery_percent") != null)
        assertTrue(station.datastream("imu_temperature_c") != null)
    }

    @Test
    fun displayStepDefaultsFollowTheColumnType() {
        val derived = AppProfile.derive(cores3)
        assertEquals(1.0, derived.displayStepOf(cores3.datastream("particles_gt03_per_01l")!!))
        assertEquals(1.0, derived.displayStepOf(cores3.datastream("battery_percent")!!))
        assertEquals(AppProfile.DEFAULT_DECIMAL_STEP, derived.displayStepOf(cores3.datastream("imu_temperature_c")!!))
        assertEquals(0, AppProfile.decimalsOf(1.0))
        assertEquals(0, AppProfile.decimalsOf(10.0))
        assertEquals(1, AppProfile.decimalsOf(0.1))
        assertEquals(1, AppProfile.decimalsOf(0.5))
        assertEquals(2, AppProfile.decimalsOf(0.05))
        assertEquals(2, AppProfile.decimalsOf(0.01))
        assertEquals(2, AppProfile.decimalsOf(0.25))
        assertEquals(1, AppProfile.decimalsOf(2.5))
        assertEquals(0, AppProfile.decimalsOf(2.0))
        assertEquals(3, AppProfile.decimalsOf(0.001))
    }

    @Test
    fun rejectsBadProfilesAndCollections() {
        assertFailsWith<IllegalArgumentException> { AppProfile.parse("""{"dict": "abc", "family": "air"}""") }
        assertFailsWith<IllegalArgumentException> {
            AppProfile.parse("""{"dict": "$CORES3_DICT", "family": "air", "display_step": {"x": 0}}""")
        }
        assertFailsWith<IllegalArgumentException> { StationCollection.parse("""{"type": "Feature", "id": "x"}""") }
    }
}
