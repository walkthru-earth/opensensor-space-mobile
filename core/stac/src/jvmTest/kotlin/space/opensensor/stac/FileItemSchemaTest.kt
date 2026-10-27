package space.opensensor.stac

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.history.OpenSensorFooter.Mobility
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.publicfeed.H3Index

/**
 * Items validated against the stac-opensensor v0.1.0 profile and extension schemas, copied from
 * stac-opensensor 815fc45 into `src/jvmTest/resources/stac-opensensor`. The example Item
 * of that commit validates too, so a failure points at the writer, not at the copy.
 */
class FileItemSchemaTest {
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val schemas: List<JsonSchema> = listOf("profile.json", "schema.json").map { name ->
        javaClass.getResourceAsStream("/stac-opensensor/$name")!!.use { factory.getSchema(it) }
    }

    private fun errors(json: String): List<String> {
        val node = mapper.readTree(json)
        return schemas.flatMap { schema -> schema.validate(node).map { it.message } }
    }

    private fun itemJson(rows: List<Row>, mobility: Mobility, template: space.opensensor.model.BoardTemplate): String {
        val result = FileItems.build(FILE_NAME, ByteArrayReader(deviceFile(rows)), footer(mobility), template, H3Index.MAX_RES, h3)
        return (result as FileItemResult.Item).json
    }

    @Test
    fun theExampleItemOfTheProfileValidates() {
        val example = javaClass.getResourceAsStream("/stac-opensensor/item.json")!!.bufferedReader().use { it.readText() }
        assertEquals(emptyList<String>(), errors(example))
    }

    @Test
    fun aFixedCoreS3ItemValidates() {
        assertEquals(emptyList<String>(), errors(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)))
    }

    @Test
    fun aMovingWaveshareItemValidates() {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        val rows = rowsIn(OSU_CELL, 3) + rowsIn(far, 3).map { it.copy(utcUs = it.utcUs!! + 30_000_000L) }
        val json = itemJson(rows, Mobility.MOVING, waveshareTemplate)
        assertTrue(json.contains("\"MultiPolygon\""))
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test
    fun theSchemasCatchAFixedItemWithoutItsCell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item["properties"] as ObjectNode).remove("grid:code")
        assertTrue(errors(mapper.writeValueAsString(item)).isNotEmpty())
    }
}
