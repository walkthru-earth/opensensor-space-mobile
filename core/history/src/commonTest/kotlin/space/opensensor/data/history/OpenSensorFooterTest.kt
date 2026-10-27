package space.opensensor.data.history

import space.opensensor.family.air.AirFamily
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

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
        assertEquals(
            OpenSensorFooter(10_000, dict, desc, thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED, purpose = "telemetry"),
            parse(opensensorFooterJson(dict, desc, 10_000, thing = "thing-a")),
        )
    }

    @Test
    fun theSpecExampleParses() {
        // stac-opensensor 4408c4e footers/cores3-telemetry-v5.json, compacted.
        val example = """{"version":"0.1.0","thing":{"id":"thing-3f2b9c1e","device_id":"441bf6e26b40","board":"m5stack-cores3","deployment_id":"unknown","mobility":"fixed"},""" +
            """"firmware":{"version":"6.14.0","schema":"cores3-telemetry-v5","boot_id":"0f3a"},""" +
            """"dictionary":{"sha256":"e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b","version":"cores3-telemetry-v6"},""" +
            """"template":{"href":"https://raw.githubusercontent.com/walkthru-earth/stac-opensensor/main/templates/cores3-telemetry-v5.json","sha256":"fb4230b3eb6e8eac881c719f900c8bd9c7e4bbd6ad247520a27368ec9d9a7218"},""" +
            """"acquisition":{"sample_interval_ms":10000,"time_semantics":"sample instant, UTC","config_id":"default","calibration_id":"unknown"},""" +
            """"file":{"rotation_interval_s":3600,"row_groups":4,"row_group_rows_max":90,"compression":"zstd","purpose":"telemetry"}}"""
        assertEquals(
            OpenSensorFooter(
                10_000,
                "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b",
                "fb4230b3eb6e8eac881c719f900c8bd9c7e4bbd6ad247520a27368ec9d9a7218",
                thingId = "thing-3f2b9c1e",
                mobility = OpenSensorFooter.Mobility.FIXED,
                purpose = "telemetry",
            ),
            parse(example),
        )
    }

    @Test
    fun mobilityIsFixedOrMoving() {
        assertEquals(OpenSensorFooter.Mobility.MOVING, parse(opensensorFooterJson(dict, desc, 10_000, mobility = "moving"))?.mobility)
        assertEquals(null, parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":"drifting"}}"""))
        assertEquals(null, parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":1}}"""))
    }

    @Test
    fun aFooterWithoutAThingIsNotTrusted() {
        assertNull(parse("""{"version":"0.1.0","dictionary":{"sha256":"$dict"}}"""))
        assertNull(parse("""{"version":"0.1.0","thing":{"mobility":"fixed"}}"""))
        assertNull(parse("""{"version":"0.1.0","thing":{"id":"thing-a"}}"""))
        // An old footer that names a station and no thing, the shape firmware wrote before the Thing rename.
        val station = stationFooterJson(dict, desc)
        assertNull(parse(station))
        assertNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.KEY to station)))
        assertNull(FileTemplates.BUNDLED.of(mapOf(OpenSensorFooter.KEY to station)).template)
    }

    @Test
    fun aGeoKeyBesideOpensensorIsIgnored() {
        val geo = """{"version":"2.0.0","primary_column":"geometry","columns":{"geometry":{"encoding":"WKB","geometry_types":["Point"],"bbox":[31.2,30.0,31.3,30.1]}}}"""
        assertEquals(OpenSensorFooter(dictionarySha256 = dict, thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), OpenSensorFooter.of(mapOf("geo" to geo, OpenSensorFooter.KEY to """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"}}""")))
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
    fun aMinorVersionAndUnknownFieldsAreRead() {
        assertEquals(OpenSensorFooter(dictionarySha256 = dict, thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), parse("""{"version":"0.2.7","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"},"extra":[1,2]}"""))
    }

    @Test
    fun leftOutFieldsAreNull() {
        // Firmware members the app does not read are ignored, whatever their value.
        assertEquals(OpenSensorFooter(thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"firmware":{"boot_id":null,"schema":["v5"]}}"""))
        assertEquals(OpenSensorFooter(thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"}}"""))
        assertEquals(OpenSensorFooter(thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"acquisition":{"time_semantics":"x"},"dictionary":{"version":"v"}}"""))
        // A non-positive interval is unknown, so the template default applies.
        assertEquals(OpenSensorFooter(thingId = "thing-a", mobility = OpenSensorFooter.Mobility.FIXED), parse("""{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"acquisition":{"sample_interval_ms":0}}"""))
    }

    @Test
    fun aBadFooterReadsAsNoFooter() {
        val bad = listOf(
            "",
            "not json",
            "{",
            "[]",
            "\"0.1.0\"",
            """{"thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"}}""",
            """{"version":0.1,"thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"}}""",
            """{"version":"1.0.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"}}""",
            """{"version":"10.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"$dict"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"${dict.uppercase()}"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"${dict.drop(1)}"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":{"sha256":"1220$dict"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"template":{"sha256":"${"g".repeat(64)}"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"template":{"sha256":null}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"dictionary":"$dict"}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"acquisition":{"sample_interval_ms":"10000"}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"acquisition":{"sample_interval_ms":10000.5}}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"acquisition":10000}""",
            """{"version":"0.1.0","thing":{"id":7,"mobility":"fixed"}}""",
            """{"version":"0.1.0","thing":{"id":"","mobility":"fixed"}}""",
            """{"version":"0.1.0","thing":"thing-a"}""",
            """{"version":"0.1.0","thing":{"id":"thing-a","mobility":"fixed"},"file":{"purpose":true}}""",
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

    private fun resolve(dict: String?, desc: String?) = FileTemplates.BUNDLED.of(mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict, desc, 10_000))).template

    @Test
    fun theBundledTemplatesResolveByTemplateDigestFirstThenByDict() {
        val waveshareDesc = waveshareTemplate.key.desc!!
        assertSame(coreS3Template, resolve(dict = null, desc = desc))
        assertSame(coreS3Template, resolve(dict, desc))
        // An unknown template digest, such as a newer firmware desc, falls back to the dict.
        assertSame(coreS3Template, resolve(dict, "ab".repeat(32)))
        // A template digest whose dict disagrees with the footer is not trusted, the dict decides.
        assertSame(coreS3Template, resolve(dict, waveshareDesc))
        assertNull(resolve("cd".repeat(32), null))
        assertNull(resolve(null, null))
    }
}
