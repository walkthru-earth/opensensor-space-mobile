package space.opensensor.stac

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.parquet.ByteArrayReader
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The stac-opensensor v0.1.0 `footer.json` schema, copied byte for byte from the v0.1.0 tag (commit
 * 83787768cc8451109d48a03d4ef5493ffcd403dd) into `src/commonTest/fixtures/stac-opensensor`, run over
 * the profile's footer fixtures. [OpenSensorFooter] must refuse what the schema refuses for a missing
 * member or another version, and read every footer the schema accepts.
 */
class FooterSchemaTest {
    private val mapper = ObjectMapper()
    private val schemaNode = mapper.readTree(stacFile("footer.json"))
    private val schema: Schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(schemaNode)

    private fun fixture(name: String): ObjectNode = mapper.readTree(stacFile("footers/$name")) as ObjectNode

    private fun errors(footer: ObjectNode): List<String> = schema.validate(footer).map { it.toString() }

    private fun parse(footer: ObjectNode): OpenSensorFooter? = OpenSensorFooter.parse(mapper.writeValueAsString(footer))

    @Test
    fun theFooterFixturesValidateAndParse() {
        for (name in FOOTERS) {
            val footer = fixture(name)
            assertEquals(name, emptyList<String>(), errors(footer))
            val parsed = parse(footer)
            assertNotNull(name, parsed)
            assertEquals(name, "telemetry", parsed!!.purpose)
        }
    }

    @Test
    fun theReaderRequiresTheMembersTheSchemaRequires() {
        val required = schemaNode.required("properties").properties()
            .filter { (_, node) -> node.has("required") }
            .associate { (obj, node) -> obj to node.required("required").values().map { it.asString() } }
        assertEquals(required, OpenSensorFooter.REQUIRED)
        val top = schemaNode.required("required").values().map { it.asString() }
        assertEquals(listOf("version") + OpenSensorFooter.REQUIRED.keys, top)
    }

    @Test
    fun aFooterMissingAnyRequiredMemberIsRefusedByBoth() {
        for (name in FOOTERS) {
            for (key in schemaNode.required("required").values().map { it.asString() }) {
                val footer = fixture(name).apply { remove(key) }
                assertTrue("$name $key", errors(footer).isNotEmpty())
                assertNull("$name $key", parse(footer))
            }
            for ((obj, fields) in OpenSensorFooter.REQUIRED) {
                for (field in fields) {
                    val footer = fixture(name).apply { (required(obj) as ObjectNode).remove(field) }
                    assertTrue("$name $obj.$field", errors(footer).isNotEmpty())
                    assertNull("$name $obj.$field", parse(footer))
                }
            }
        }
    }

    @Test
    fun onlyVersion01WithAnyPatchIsAcceptedByBoth() {
        for (version in listOf("0.1.0", "0.1.1", "0.1.42")) {
            val footer = fixture(FOOTERS.first()).put("version", version)
            assertEquals(version, emptyList<String>(), errors(footer))
            assertNotNull(version, parse(footer))
        }
        for (version in listOf("0.2.0", "0.0.1", "1.1.0", "0.1", "0.10.0")) {
            val footer = fixture(FOOTERS.first()).put("version", version)
            assertTrue(version, errors(footer).isNotEmpty())
            assertNull(version, parse(footer))
        }
    }

    @Test
    fun aBenchmarkFooterValidatesButItsFileGetsNoItem() {
        val footer = fixture(FOOTERS.first()).apply { (required("file") as ObjectNode).put("purpose", BENCHMARK) }
        assertEquals(emptyList<String>(), errors(footer))
        val parsed = parse(footer)
        assertNotNull(parsed)
        assertEquals(BENCHMARK, parsed!!.purpose)
        val result = FileItems.build(FILE_NAME, ByteArrayReader(deviceFile(rowsIn(OSU_CELL))), parsed, coreS3Template, PUBLISHED_RES, h3)
        assertEquals(FileItemResult.NotTelemetry(BENCHMARK), result)
    }

    private companion object {
        val FOOTERS = listOf("m5stack-cores3.json", "waveshare-esp32-s3-sim7670g.json")
        const val BENCHMARK = "codec-comparison-duplicate-rows"
    }
}
