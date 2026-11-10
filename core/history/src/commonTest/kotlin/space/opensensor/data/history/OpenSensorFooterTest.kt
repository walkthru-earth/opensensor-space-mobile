package space.opensensor.data.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import space.opensensor.family.air.AirFamily
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenSensorFooterTest {
    init {
        AirFamily.install()
    }

    private val dict = coreS3Template.dict
    private val desc = coreS3Template.key.desc!!

    private fun parse(json: String) = OpenSensorFooter.parse(json)

    @Test
    fun theFooterKeyIsOpensensor() {
        assertEquals("opensensor", OpenSensorFooter.KEY)
    }

    @Test
    fun aFullFooterGivesOnlyTheFieldsTheAppReads() {
        assertEquals(thingA, parse(opensensorFooterJson(dict, desc, 10_000, thing = "thing-a")))
    }

    /** The full footer [opensensorFooterJson] writes, as a JSON object to edit. */
    private fun full(): JsonObject = Json.parseToJsonElement(opensensorFooterJson(dict, desc, 10_000)).jsonObject

    /** [this] footer with [field] of object [obj] set to [value], or left out when [value] is null. */
    private fun JsonObject.with(obj: String, field: String, value: JsonElement?): String {
        val parent = getValue(obj).jsonObject
        val changed = JsonObject(if (value == null) parent - field else parent + (field to value))
        return JsonObject(this + (obj to changed)).toString()
    }

    /** [this] footer with top level member [key] set to [value], or left out when [value] is null. */
    private fun JsonObject.with(key: String, value: JsonElement?): String =
        JsonObject(if (value == null) this - key else this + (key to value)).toString()

    private val thingA = OpenSensorFooter(
        10_000,
        dict,
        desc,
        templateHref = "https://example.invalid/templates/m5stack-cores3/$desc.json",
        thingId = "thing-a",
        mobility = OpenSensorFooter.Mobility.FIXED,
        purpose = "telemetry",
        rotationIntervalS = 3600,
    )

    @Test
    fun aTemplateHrefOfTheWrongKindIsLeftOut() {
        fun href(value: String) = parse(full().with("template", "href", Json.parseToJsonElement(value)))
        assertEquals("https://example.invalid/t.json", href("\"https://example.invalid/t.json\"")?.templateHref)
        // REQUIRED checks presence and non null only. A href of the wrong kind leaves the field null and
        // keeps the rest of the footer.
        for (bad in listOf("\"\"", "7", "true", "{}", "[\"x\"]")) {
            assertEquals(bad, thingA.copy(templateHref = null), href(bad))
        }
        // Left out or JSON null, the required member is missing, so there is no footer.
        assertNull(href("null"))
        assertNull(parse(full().with("template", "href", null)))
    }

    @Test
    fun theRotationWindowIsOneTheProfileAllows() {
        fun window(value: String) = parse(full().with("file", "rotation_interval_s", Json.parseToJsonElement(value)))
        for (s in listOf(600L, 900L, 1800L, 3600L)) assertEquals(s, window("$s")?.rotationIntervalS)
        // A window of another value or kind leaves the field null and keeps the rest of the footer.
        for (bad in listOf("700", "0", "\"900\"", "900.5", "true", "{}", "[900]")) {
            val footer = window(bad)
            assertEquals(bad, "thing-a", footer?.thingId)
            assertEquals(bad, null, footer?.rotationIntervalS)
        }
    }

    @Test
    fun theSpecExampleParses() {
        // stac-opensensor v0.1.0 (83787768) footers/m5stack-cores3.json, compacted, with the pinned
        // opensensor-space-firmware raw URLs a CoreS3 board writes. Its long free text members are cut short.
        val example = """{"version":"0.1.0","thing":{"id":"3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13","device_id":"441bf6e26b40","board":"m5stack-cores3","deployment_id":"unknown","mobility":"fixed"},""" +
            """"firmware":{"version":"idf-cores3-parquet-v6.23","schema":"cores3-telemetry-v6","boot_id":"0f3a9c2e5b7d41e8a6c3f0b2d9e4a713"},""" +
            """"dictionary":{"sha256":"3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9","version":"cores3-telemetry-v7",""" +
            """"uri":"https://raw.githubusercontent.com/walkthru-earth/opensensor-space-firmware/093f2b8e45cd2b9e24e1324ad9fa2e6e20ad1d47/firmware/esp-idf-cores3/bringup/telemetry_fields.inc"},""" +
            """"template":{"href":"https://raw.githubusercontent.com/walkthru-earth/opensensor-space-firmware/bb618f32a8a9bfe6a633a22ad5e99d319d55d46b/docs/shared/templates/m5stack-cores3/3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9.json",""" +
            """"sha256":"4dd6c4b1ef19187ed2c011ab86018e43be0f28edc3af63a0f6a27b70ff203ef3"},""" +
            """"acquisition":{"sample_interval_ms":10000,"time_semantics":"event_time_utc_us estimates snapshot start","config_id":"cores3-acquisition-v3","calibration_id":"unknown"},""" +
            """"file":{"rotation_interval_s":900,"row_groups":1,"row_group_rows_max":90,"compression":"LZ4_RAW","purpose":"telemetry"}}"""
        val footer = OpenSensorFooter(
            10_000,
            "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9",
            "4dd6c4b1ef19187ed2c011ab86018e43be0f28edc3af63a0f6a27b70ff203ef3",
            templateHref = "https://raw.githubusercontent.com/walkthru-earth/opensensor-space-firmware/bb618f32a8a9bfe6a633a22ad5e99d319d55d46b/docs/shared/templates/m5stack-cores3/3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9.json",
            thingId = "3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13",
            mobility = OpenSensorFooter.Mobility.FIXED,
            purpose = "telemetry",
            rotationIntervalS = 900,
        )
        assertEquals(footer, parse(example))
        assertSame(coreS3Template, FileTemplates.BUNDLED.of(footer).template)
        // The href a CoreS3 board writes is one TemplateHrefs would fetch.
        assertTrue(TemplateHrefs.isFirmwareTemplate(assertNotNull(footer.templateHref)))
    }

    @Test
    fun mobilityIsFixedOrMoving() {
        assertEquals(OpenSensorFooter.Mobility.MOVING, parse(opensensorFooterJson(dict, desc, 10_000, mobility = "moving"))?.mobility)
        assertNull(parse(full().with("thing", "mobility", JsonPrimitive("drifting"))))
        assertNull(parse(full().with("thing", "mobility", JsonPrimitive(1))))
    }

    @Test
    fun everyMemberTheFooterSchemaRequiresIsRequired() {
        assertEquals(thingA, parse(full().toString()))
        assertNull(parse(full().with("version", null)))
        for ((obj, fields) in OpenSensorFooter.REQUIRED) {
            assertNull(parse(full().with(obj, null)), obj)
            assertNull(parse(full().with(obj, JsonNull)), obj)
            assertNull(parse(full().with(obj, JsonPrimitive("x"))), obj)
            for (field in fields) {
                assertNull(parse(full().with(obj, field, null)), "$obj.$field")
                assertNull(parse(full().with(obj, field, JsonNull)), "$obj.$field null")
            }
        }
        // The same members as stac-opensensor v0.1.0 json-schema/footer.json. FooterSchemaTest checks it there.
        assertEquals(
            mapOf(
                "thing" to listOf("id", "device_id", "board", "mobility"),
                "firmware" to listOf("version", "schema", "boot_id"),
                "dictionary" to listOf("sha256"),
                "template" to listOf("href", "sha256"),
                "acquisition" to listOf("sample_interval_ms", "time_semantics"),
                "file" to listOf("rotation_interval_s", "row_groups", "row_group_rows_max", "compression", "purpose"),
            ),
            OpenSensorFooter.REQUIRED,
        )
    }

    @Test
    fun aFooterWithoutAThingIsNotTrusted() {
        // An old footer that names a station and no thing, the shape firmware wrote before the Thing rename.
        val station = stationFooterJson(dict, desc)
        assertNull(parse(station))
        assertNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.KEY to station)))
        assertNull(FileTemplates.BUNDLED.of(mapOf(OpenSensorFooter.KEY to station)).template)
    }

    @Test
    fun aGeoKeyBesideOpensensorIsIgnored() {
        val geo = """{"version":"2.0.0","primary_column":"geometry","columns":{"geometry":{"encoding":"WKB","geometry_types":["Point"],"bbox":[31.2,30.0,31.3,30.1]}}}"""
        assertEquals(thingA, OpenSensorFooter.of(mapOf("geo" to geo, OpenSensorFooter.KEY to full().toString())))
    }

    @Test
    fun aGeoKeyAloneIsNoFooter() {
        val geo = """{"version":"2.0.0","primary_column":"geometry","columns":{"geometry":{"encoding":"WKB","geometry_types":["Point"],"bbox":[31.2,30.0,31.3,30.1]}}}"""
        assertNull(OpenSensorFooter.of(mapOf("geo" to geo)))
        // Even a geo value shaped like a footer is not read, only the opensensor key is.
        assertNull(OpenSensorFooter.of(mapOf("geo" to opensensorFooterJson(dict, desc, 10_000))))
        assertNull(FileTemplates.BUNDLED.of(mapOf("geo" to opensensorFooterJson(dict, desc, 10_000))).template)
    }

    @Test
    fun anyPatchOfVersion01AndUnknownMembersAreRead() {
        for (version in listOf("0.1.0", "0.1.7", "0.1.12")) {
            assertEquals(version, thingA, parse(full().with("version", JsonPrimitive(version))))
        }
        assertEquals(thingA, parse(full().with("extra", Json.parseToJsonElement("[1,2]"))))
        assertEquals(thingA, parse(full().with("file", "extra", JsonPrimitive("x"))))
    }

    @Test
    fun anotherMajorOrMinorVersionIsRefused() {
        // While the contract is 0.x a minor bump breaks readers, so only 0.1 with any patch is read.
        for (version in listOf("0.2.0", "0.0.9", "0.10.0", "1.1.0", "10.1.0", "0.1", "0.1.0-rc.1", "v0.1.0", " 0.1.0", "")) {
            assertNull(parse(full().with("version", JsonPrimitive(version))), version)
        }
        assertNull(parse(full().with("version", JsonPrimitive(0.1))))
    }

    @Test
    fun optionalMembersAndUnreadValuesAreIgnored() {
        // Members footer.json does not require may be left out.
        var footer = full()
        val optional = listOf(
            "thing" to "deployment_id",
            "dictionary" to "version",
            "dictionary" to "uri",
            "acquisition" to "config_id",
            "acquisition" to "config",
            "acquisition" to "calibration_id",
        )
        for ((obj, field) in optional) footer = Json.parseToJsonElement(footer.with(obj, field, null)).jsonObject
        assertEquals(thingA, parse(footer.toString()))
        // A required member the app does not read is only checked for presence, whatever its value.
        assertEquals(thingA, parse(full().with("firmware", "schema", Json.parseToJsonElement("""["v5"]"""))))
        // A non-positive interval is unknown, so the template default applies.
        assertEquals(thingA.copy(sampleIntervalMs = null), parse(full().with("acquisition", "sample_interval_ms", JsonPrimitive(0))))
    }

    @Test
    fun aBadFooterReadsAsNoFooter() {
        val bad = listOf(
            "",
            "not json",
            "{",
            "[]",
            "\"0.1.0\"",
            full().with("dictionary", "sha256", JsonPrimitive(dict.uppercase())),
            full().with("dictionary", "sha256", JsonPrimitive(dict.drop(1))),
            full().with("dictionary", "sha256", JsonPrimitive("1220$dict")),
            full().with("template", "sha256", JsonPrimitive("g".repeat(64))),
            full().with("dictionary", JsonPrimitive(dict)),
            full().with("acquisition", "sample_interval_ms", JsonPrimitive("10000")),
            full().with("acquisition", "sample_interval_ms", JsonPrimitive(10000.5)),
            full().with("acquisition", JsonPrimitive(10000)),
            full().with("thing", "id", JsonPrimitive(7)),
            full().with("thing", "id", JsonPrimitive("")),
            full().with("thing", JsonPrimitive("thing-a")),
            full().with("file", "purpose", JsonPrimitive(true)),
            full().with("file", "purpose", JsonPrimitive("")),
        )
        for (json in bad) assertNull(parse(json), json)
    }

    @Test
    fun aFileWithoutTheKeyOrWithOnlyTheOldKeysHasNoFooter() {
        assertNull(OpenSensorFooter.of(emptyMap()))
        val old = LegacyFooterKeys.footer(
            dict = dict,
            desc = desc,
            intervalMs = 10_000,
            thing = "thing-a",
            boot = "0f3a",
            schema = "cores3-telemetry-v3",
            purpose = "telemetry",
        )
        assertNull(OpenSensorFooter.of(old))
        val file = FileTemplates.BUNDLED.of(old)
        assertNull(file.template)
        assertNull(file.footer)
        assertEquals(0L, file.sampleIntervalMs)
    }

    @Test
    fun aRealFirmwareTemplateDigestResolvesWithoutADict() {
        // The template.sha256 firmware writes for the CoreS3 template, with no dictionary member.
        val real = "4dd6c4b1ef19187ed2c011ab86018e43be0f28edc3af63a0f6a27b70ff203ef3"
        assertSame(coreS3Template, FileTemplates.BUNDLED.template(null, real))
        assertSame(coreS3Template, resolve(null, real))
    }

    @Test
    fun aDayFileIsReadOnlyFromItsAppPrivateKey() {
        val day = AppDay(10_000, dict, desc)
        assertEquals(day, AppDay.of(mapOf(AppDay.KEY to day.encode())))
        assertNull(AppDay.of(mapOf(OpenSensorFooter.KEY to day.encode())))
        assertNull(OpenSensorFooter.of(mapOf(AppDay.KEY to day.encode())))
        // The day value is not a footer, so even under the footer key it is not read as one.
        assertNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.KEY to day.encode())))
        assertFalse(AppDay.KEY.startsWith(OpenSensorFooter.KEY))
        assertSame(coreS3Template, FileTemplates.BUNDLED.ofDay(mapOf(AppDay.KEY to day.encode())).template)
    }

    @Test
    fun appDayRoundTripsAndRefusesAnotherVersion() {
        for (day in listOf(AppDay(10_000, dict, desc), AppDay(sampleIntervalMs = 30_000), AppDay(dict = dict))) {
            assertEquals(day, AppDay.parse(day.encode()))
        }
        assertEquals("""{"app_day_version":1,"interval_ms":10000,"dict":"$dict","desc":"$desc"}""", AppDay(10_000, dict, desc).encode())
        assertNull(AppDay.parse("""{"app_day_version":2,"interval_ms":10000}"""))
        assertNull(AppDay.parse("""{"app_day_version":"1","interval_ms":10000}"""))
        assertNull(AppDay.parse("""{"interval_ms":10000}"""))
        assertNull(AppDay.parse("""{"app_day_version":1,"interval_ms":"10000"}"""))
        assertNull(AppDay.parse("""{"app_day_version":1,"interval_ms":0}"""))
        assertNull(AppDay.parse("""{"app_day_version":1,"dict":"ABC"}"""))
        assertNull(AppDay.parse("not json"))
    }

    private fun resolve(dict: String?, desc: String?) = FileTemplates.BUNDLED.template(dict, desc)

    private fun resolveFooter(dict: String, desc: String) = FileTemplates.BUNDLED.of(mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict, desc, 10_000))).template

    @Test
    fun theBundledTemplatesResolveByTemplateDigestAndByDictOnlyWithoutOne() {
        val waveshareDesc = waveshareTemplate.key.desc!!
        assertSame(coreS3Template, resolveFooter(dict, desc))
        assertSame(coreS3Template, resolve(dict = null, desc = desc))
        assertSame(coreS3Template, resolve(dict, desc))
        // An unknown template digest, such as a newer firmware desc, never falls back to the dict (#259).
        assertNull(resolve(dict, "ab".repeat(32)))
        assertNull(resolveFooter(dict, "ab".repeat(32)))
        // A template digest whose dict disagrees with the footer is not used.
        assertNull(resolve(dict, waveshareDesc))
        assertNull(resolveFooter(dict, waveshareDesc))
        assertNull(resolve("cd".repeat(32), null))
        assertNull(resolveFooter("cd".repeat(32), "ab".repeat(32)))
        assertNull(resolve(null, null))
    }
}
