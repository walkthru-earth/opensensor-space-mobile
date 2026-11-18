package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThingTest {
    private val cores3 = BoardTemplate.parse(CORES3_TEMPLATE, desc = DESC_A)
    private val profile = AppProfile.parse(CORES3_PROFILE)
    private val collection = ThingCollection.parse(CORES3_COLLECTION)

    @Test
    fun parsesTheAppProfileOverlay() {
        assertEquals(CORES3_DICT, profile.dict)
        assertEquals(Family("air"), profile.family)
        assertEquals("mood", profile.hero)
        assertEquals(setOf("accel_z_g"), profile.hidden)
        assertTrue(profile.labels.isEmpty())
        assertNull(profile.primary)
    }

    @Test
    fun parsesTheThingCollection() {
        assertEquals("thing-3f2b9c1e", collection.id)
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
    fun joinLaysTheOverlayOnTheDerivedProfile() {
        val thing = Thing.join("3f2b9c1e", cores3, profile, collection, deviceId = 7, h3Cell = "887576970bfffff")
        assertEquals(CORES3_DICT, thing.dict)
        assertEquals(Family("air"), thing.profile.family)
        assertEquals("mood", thing.profile.hero)
        // The Collection scale makes PM2.5 the first measurement with a scale.
        assertEquals("pm25_atmospheric_ug_m3", thing.primary?.name)
        val pm25 = thing.datastream("pm25_atmospheric_ug_m3")!!
        // The Collection title is untagged, so every language reads it.
        assertEquals("PM2.5", pm25.label("ar"))
        assertEquals("who-2021-pm25", pm25.scale?.id)
        assertEquals(5.0, pm25.scale?.guideline)
        assertEquals("µg/m³", pm25.displayUnit)
        // Registry decimals for PM2.5, the integer default for a column the registry lacks.
        assertEquals(1, pm25.decimals)
        assertEquals(0, thing.datastream("battery_percent")!!.decimals)
        assertEquals(2, thing.datastream("imu_temperature_c")!!.decimals)
        assertEquals("°C", thing.datastream("imu_temperature_c")!!.displayUnit)
        assertEquals("Particle sensor status", thing.datastream("pms_status")!!.label("en"))
        // The registry label of PM10, which has the registry WHO scale.
        assertEquals("PM10 mass concentration", thing.datastream("pm10_atmospheric_ug_m3")!!.label("en"))
        assertEquals("تركيز كتلة الجسيمات PM10", thing.datastream("pm10_atmospheric_ug_m3")!!.label("ar"))
        assertEquals("who-2021-pm10", thing.datastream("pm10_atmospheric_ug_m3")!!.scale?.id)
        // The CF=1 column has a registry entry without a scale, so it stays neutral.
        assertNull(thing.datastream("pm25_cf1_ug_m3")!!.scale)
        assertEquals("pm25_atmospheric_ug_m3", thing.datastreamForLiveKey("pm25")?.name)
        assertNull(thing.datastreamForLiveKey("zzz"))
    }

    @Test
    fun aHiddenColumnIsNeverPrimary() {
        val hidden = profile.copy(hidden = setOf("pm25_atmospheric_ug_m3", "pm10_atmospheric_ug_m3"))
        assertEquals("pm1_atmospheric_ug_m3", Thing.join("s", cores3, hidden).primary?.name)
        val named = collection.copy(primary = "pm25_atmospheric_ug_m3")
        assertEquals("pm1_atmospheric_ug_m3", Thing.join("s", cores3, hidden, named).primary?.name)
    }

    @Test
    fun theCollectionPrimaryWins() {
        val named = ThingCollection.parse(CORES3_COLLECTION.replace("\"title\": \"Osu thing\",", "\"title\": \"Osu thing\", \"opensensor:primary\": \"battery_percent\","))
        assertEquals("battery_percent", named.primary)
        assertEquals("battery_percent", Thing.join("s", cores3, collection = named).primary?.name)
        // A primary the template lacks is skipped.
        assertEquals("pm25_atmospheric_ug_m3", Thing.join("s", cores3, collection = named.copy(primary = "nope")).primary?.name)
    }

    @Test
    fun aTemplateWithoutACelsiusColumnStillResolvesAThing() {
        val kelvin = BoardTemplate.parse(
            template(
                """{"name": "air_temperature_k", "type": "float", "unit": "K", "roles": ["physicalMeasurement"]},
                   {"name": "pm25_atmospheric_ug_m3", "type": "float", "unit": "ug/m3", "roles": ["physicalMeasurement"]}""",
                liveKeys = """{"air_temperature_k": "tk", "pm25_atmospheric_ug_m3": "pm25"}""",
            ),
        )
        val thing = Thing.join("3f2b9c1e", kelvin)
        assertEquals(setOf("tk", "pm25"), thing.live.keys)
        assertNull(thing.live.temperature, "only a Cel column is the temperature card")
        assertEquals("K", thing.datastream("air_temperature_k")!!.datastream.unit)
    }

    @Test
    fun derivedProfileWhenNoneIsBundled() {
        val thing = Thing.join("3f2b9c1e", cores3)
        assertEquals(Family.GENERIC, thing.profile.family)
        assertNull(thing.profile.hero)
        // The first physicalMeasurement column with a registry scale.
        assertEquals("pm25_atmospheric_ug_m3", thing.primary?.name)
        assertEquals("PM2.5 mass concentration", thing.datastream("pm25_atmospheric_ug_m3")!!.label("en"))
        assertEquals("PM1 mass concentration", thing.datastream("pm1_atmospheric_ug_m3")!!.label("en"))
        assertNull(thing.datastream("pm1_atmospheric_ug_m3")!!.scale, "PM1 has no registry scale")
    }

    @Test
    fun derivedProfileFallsBackToTheFirstMeasurement() {
        val t = BoardTemplate.parse(
            template(
                """
                {"name": "status", "type": "int32", "roles": ["qualityInformation"]},
                {"name": "battery", "type": "int32", "roles": ["auxiliaryInformation"]},
                {"name": "soil_moisture_pct", "type": "float", "unit": "%", "roles": ["physicalMeasurement"]}
                """,
            ),
        )
        assertEquals("soil_moisture_pct", AppProfile.derive(t).primary)
        assertEquals("soil moisture pct", Thing.join("s", t).datastream("soil_moisture_pct")!!.label("ar"))
        val none = BoardTemplate.parse(template("""{"name": "battery", "type": "int32", "roles": ["auxiliaryInformation"]}"""))
        assertNull(AppProfile.derive(none).primary)
        assertNull(Thing.join("s", none).primary)
    }

    @Test
    fun profileForAnotherDictionaryIsNotUsed() {
        val other = BoardTemplate.parse(template("""{"name": "pm25_atmospheric_ug_m3", "type": "int32", "roles": ["physicalMeasurement"]}"""))
        val thing = Thing.join("s", other, profile)
        assertEquals(Family.GENERIC, thing.profile.family)
        assertEquals(OTHER_DICT, thing.profile.dict)
    }

    @Test
    fun aScaleInAnotherUnitIsNeverApplied() {
        val ppb = collection.copy(scales = collection.scales.mapValues { (_, s) -> s.copy(unit = "[ppb]") })
        assertNull(Thing.join("s", cores3, collection = ppb).datastream("pm25_atmospheric_ug_m3")!!.scale)
    }

    @Test
    fun runtimeSampleIntervalWins() {
        assertEquals(10.0, Thing.join("s", cores3).sampleIntervalS)
        assertEquals(30.0, Thing.join("s", cores3, collection = collection).sampleIntervalS)
        assertEquals(60.0, Thing.join("s", cores3, collection = collection, runtimeSampleIntervalS = 60.0).sampleIntervalS)
        assertEquals(30.0, Thing.join("s", cores3, collection = collection, runtimeSampleIntervalS = 0.0).sampleIntervalS)
    }

    @Test
    fun fileFooterSampleIntervalWinsForThatFile() {
        val thing = Thing.join("s", cores3, collection = collection, runtimeSampleIntervalS = 60.0)
        assertEquals(5.0, thing.sampleIntervalFor(footerSampleIntervalMs = 5_000))
        assertEquals(0.5, thing.sampleIntervalFor(footerSampleIntervalMs = 500))
        assertEquals(60.0, thing.sampleIntervalFor(footerSampleIntervalMs = 0))
        assertEquals(60.0, thing.sampleIntervalFor(footerSampleIntervalMs = null))
    }

    @Test
    fun thingKeepsEveryDatastreamWhateverItsRole() {
        val thing = Thing.join("s", cores3)
        assertEquals(cores3.datastreams.map { it.name }, thing.datastreams.map { it.name })
        assertTrue(thing.datastream("battery_percent") != null)
        assertTrue(thing.datastream("imu_temperature_c") != null)
    }

    @Test
    fun decimalsFollowTheColumnThenTheRegistryThenTheType() {
        val derived = AppProfile.derive(cores3)
        assertEquals(0, derived.decimalsOf(cores3.datastream("particles_gt03_per_01l")!!))
        assertEquals(0, derived.decimalsOf(cores3.datastream("battery_percent")!!))
        assertEquals(2, derived.decimalsOf(cores3.datastream("imu_temperature_c")!!))
        assertEquals(1, derived.decimalsOf(cores3.datastream("pm25_atmospheric_ug_m3")!!))
        val hinted = BoardTemplate.parse(
            template(
                """{"name": "pm25_atmospheric_ug_m3", "type": "float", "unit": "ug/m3", "roles": ["physicalMeasurement"],
                "opensensor:property": "http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/", "opensensor:decimals": 3}"""
            ),
        )
        assertEquals(3, AppProfile.derive(hinted).decimalsOf(hinted.datastreams.single()))
    }

    @Test
    fun rejectsBadProfilesAndCollections() {
        assertFailsWith<IllegalArgumentException> { AppProfile.parse("""{"dict": "abc", "family": "air"}""") }
        assertFailsWith<IllegalArgumentException> { ThingCollection.parse("""{"type": "Feature", "id": "x"}""") }
    }

    @Test
    fun rejectsScalesWhoseLaterStepsCannotBeReached() {
        fun scale(vararg uppers: Double?) =
            Scale("s", "https://example.org", uppers.map { ScaleStep(it, LocalizedText.of("x")) })
        assertFailsWith<IllegalArgumentException> { scale(null, 10.0) }
        assertFailsWith<IllegalArgumentException> { scale(10.0, 5.0, null) }
        assertFailsWith<IllegalArgumentException> { scale(10.0, 10.0) }
        assertFailsWith<IllegalArgumentException> { scale(Double.NaN, null) }
        assertEquals(1, scale(5.0, 10.0, null).stepIndexOf(7.0))
        assertEquals(2, scale(5.0, 10.0, null).stepIndexOf(70.0))
    }
}
