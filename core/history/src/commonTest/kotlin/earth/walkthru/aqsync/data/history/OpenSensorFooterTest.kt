package earth.walkthru.aqsync.data.history

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class OpenSensorFooterTest {
    private val dict = coreS3Template.dict
    private val desc = coreS3Template.key.desc!!

    private fun parse(json: String) = OpenSensorFooter.parse(json)

    @Test
    fun aFullFooterGivesOnlyTheFieldsTheAppReads() {
        assertEquals(
            OpenSensorFooter(10_000, dict, desc, stationId = "station-a", purpose = "telemetry"),
            parse(opensensorFooterJson(dict, desc, 10_000, station = "station-a")),
        )
    }

    @Test
    fun theSpecExampleParses() {
        // stac-opensensor a56dfb0 footers/cores3-telemetry-v5.json, compacted.
        val example = """{"version":"0.1.0","station":{"id":"station-3f2b9c1e","device_id":"441bf6e26b40","board":"m5stack-cores3","deployment_id":"unknown"},""" +
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
                stationId = "station-3f2b9c1e",
                purpose = "telemetry",
            ),
            parse(example),
        )
    }

    @Test
    fun aMinorVersionAndUnknownFieldsAreRead() {
        assertEquals(OpenSensorFooter(dictionarySha256 = dict), parse("""{"version":"0.2.7","dictionary":{"sha256":"$dict"},"extra":[1,2]}"""))
    }

    @Test
    fun leftOutFieldsAreNull() {
        // Firmware members the app does not read are ignored, whatever their value.
        assertEquals(OpenSensorFooter(), parse("""{"version":"0.1.0","firmware":{"boot_id":null,"schema":["v5"]}}"""))
        assertEquals(OpenSensorFooter(), parse("""{"version":"0.1.0"}"""))
        assertEquals(OpenSensorFooter(), parse("""{"version":"0.1.0","acquisition":{"time_semantics":"x"},"dictionary":{"version":"v"}}"""))
        // A non-positive interval is unknown, so the template default applies.
        assertEquals(OpenSensorFooter(), parse("""{"version":"0.1.0","acquisition":{"sample_interval_ms":0}}"""))
    }

    @Test
    fun aBadFooterReadsAsNoFooter() {
        val bad = listOf(
            "",
            "not json",
            "{",
            "[]",
            "\"0.1.0\"",
            """{"dictionary":{"sha256":"$dict"}}""",
            """{"version":0.1,"dictionary":{"sha256":"$dict"}}""",
            """{"version":"1.0.0","dictionary":{"sha256":"$dict"}}""",
            """{"version":"10.1.0","dictionary":{"sha256":"$dict"}}""",
            """{"version":"0.1.0","dictionary":{"sha256":"${dict.uppercase()}"}}""",
            """{"version":"0.1.0","dictionary":{"sha256":"${dict.drop(1)}"}}""",
            """{"version":"0.1.0","dictionary":{"sha256":"1220$dict"}}""",
            """{"version":"0.1.0","template":{"sha256":"${"g".repeat(64)}"}}""",
            """{"version":"0.1.0","template":{"sha256":null}}""",
            """{"version":"0.1.0","dictionary":"$dict"}""",
            """{"version":"0.1.0","acquisition":{"sample_interval_ms":"10000"}}""",
            """{"version":"0.1.0","acquisition":{"sample_interval_ms":10000.5}}""",
            """{"version":"0.1.0","acquisition":10000}""",
            """{"version":"0.1.0","station":{"id":7}}""",
            """{"version":"0.1.0","station":{"id":""}}""",
            """{"version":"0.1.0","station":"station-a"}""",
            """{"version":"0.1.0","file":{"purpose":true}}""",
        )
        for (json in bad) assertNull(parse(json), json)
    }

    @Test
    fun aFileWithoutTheKeyOrWithOnlyTheOldKeysHasNoFooter() {
        assertNull(OpenSensorFooter.of(emptyMap()))
        val old = mapOf(
            "dictionary_sha256" to dict,
            "sample_interval_ms" to "10000",
            "template_sha256" to desc,
            "station_id" to "station-a",
            "boot_id" to "0f3a",
            "schema_version" to "cores3-telemetry-v3",
            "purpose" to "telemetry",
        )
        assertNull(OpenSensorFooter.of(old))
        val file = FileTemplates.BUNDLED.of(old)
        assertNull(file.template)
        assertNull(file.footer)
        assertEquals(0L, file.sampleIntervalMs)
    }

    @Test
    fun aDayFileIsReadOnlyFromItsAppKey() {
        val day = OpenSensorFooter(10_000, dict, desc)
        assertEquals(day, OpenSensorFooter.ofDay(mapOf(OpenSensorFooter.DAY_KEY to day.encode())))
        assertNull(OpenSensorFooter.ofDay(mapOf(OpenSensorFooter.KEY to day.encode())))
        assertNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.DAY_KEY to day.encode())))
        assertSame(coreS3Template, FileTemplates.BUNDLED.ofDay(mapOf(OpenSensorFooter.DAY_KEY to day.encode())).template)
    }

    @Test
    fun encodeRoundTrips() {
        val full = OpenSensorFooter(10_000, dict, desc, stationId = "station-a", purpose = "telemetry")
        for (footer in listOf(full, OpenSensorFooter(10_000, dict, desc), OpenSensorFooter(sampleIntervalMs = 30_000), OpenSensorFooter(dictionarySha256 = dict))) {
            assertEquals(footer, parse(footer.encode()))
        }
        assertEquals("""{"version":"0.1.0","dictionary":{"sha256":"$dict"},"template":{"sha256":"$desc"},"acquisition":{"sample_interval_ms":10000}}""", OpenSensorFooter(10_000, dict, desc).encode())
    }

    private fun resolve(dict: String?, desc: String?) = FileTemplates.BUNDLED.of(mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict, desc, 10_000))).template

    @Test
    fun theBundledTemplatesResolveByTemplateDigestFirstThenByDict() {
        val waveshareDesc = waveshareTemplate.key.desc!!
        assertSame(coreS3Template, resolve(dict = null, desc = desc))
        assertSame(coreS3Template, resolve(dict, desc))
        // An unknown template digest, as real firmware writes until the bundled bytes match, falls back to the dict.
        assertSame(coreS3Template, resolve(dict, "ab".repeat(32)))
        // A template digest whose dict disagrees with the footer is not trusted, the dict decides.
        assertSame(coreS3Template, resolve(dict, waveshareDesc))
        assertNull(resolve("cd".repeat(32), null))
        assertNull(resolve(null, null))
    }
}
