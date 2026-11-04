package space.opensensor.stac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.history.OpenSensorFooter.Mobility
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng

/**
 * Items validated against every schema a STAC validator applies to them, with no network.
 *
 * - The stac-opensensor v0.1.0 profile and extension schemas and its fixed and moving example Items,
 *   copied from the v0.1.0 tag (commit 9943282) into `src/jvmTest/resources/stac-opensensor`. The
 *   examples validate too, so a failure points at the writer, not at the copy.
 * - The core STAC 1.1.0 Item schema with the GeoJSON Feature and Geometry schemas it references, and
 *   the Grid v1.1.0, Anonymized Location v1.0.0, Table v1.3.0 and File v2.1.0 extension schemas.
 *   They are copied from their `$id` URLs on 2026-11-04 into `src/jvmTest/resources/schemas/`, one
 *   folder per host, with only line endings normalized. Every `https://` reference resolves there.
 *   As in any STAC validator, an extension schema applies to the Items that list it in `stac_extensions`.
 *
 * Items are built at the published resolution, 8, as `PublishGate` builds them for a board. A phone
 * Item rebuilt from the file behind each example equals that example as JSON values, the board's Item.
 */
class FileItemSchemaTest {
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7) { builder ->
        builder.schemaMappers { it.mapPrefix("https://", "classpath:schemas/") }
    }
    private val profileSchemas: List<JsonSchema> = listOf("profile.json", "schema.json").map { name ->
        javaClass.getResourceAsStream("/stac-opensensor/$name")!!.use { factory.getSchema(it) }
    }
    private val coreSchema: JsonSchema = factory.getSchema(SchemaLocation.of(CORE_ITEM))

    /** The bundled schema of each STAC extension the writer can list, by its URL. */
    private val extensionSchemas: Map<String, JsonSchema> = FileItems.EXTENSIONS
        .filter { it.startsWith(STAC_EXTENSIONS) }
        .associateWith { factory.getSchema(SchemaLocation.of(it)) }

    /** Every schema the Item [json] answers to, the profile, core STAC and each extension it lists. */
    private fun errors(json: String): List<String> {
        val node = mapper.readTree(json)
        val listed = node["stac_extensions"].map { it.asText() }.filter { it.startsWith(STAC_EXTENSIONS) }
        val schemas = profileSchemas + coreSchema + listed.map { checkNotNull(extensionSchemas[it]) { "no bundled schema for $it" } }
        return schemas.flatMap { schema -> schema.validate(node).map { it.message } }
    }

    private fun coreErrors(json: String): List<String> = coreSchema.validate(mapper.readTree(json)).map { it.message }

    private fun example(name: String): String = javaClass.getResourceAsStream("/stac-opensensor/$name")!!.bufferedReader().use { it.readText() }

    private fun itemJson(
        rows: List<Row>,
        mobility: Mobility,
        template: space.opensensor.model.BoardTemplate,
        name: String = FILE_NAME,
        thing: String = THING,
    ): String {
        val result = FileItems.build(name, ByteArrayReader(deviceFile(rows)), footer(mobility, thing), template, PUBLISHED_RES, h3)
        return (result as FileItemResult.Item).json
    }

    private fun movingRows(): List<Row> {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        return rowsIn(OSU_CELL, 3) + rowsIn(far, 3).map { it.copy(utcUs = it.utcUs!! + 30_000_000L) }
    }

    @Test
    fun everyStacExtensionTheWriterListsHasABundledSchema() {
        assertEquals(4, extensionSchemas.size)
    }

    @Test
    fun theExampleItemsOfTheProfileValidate() {
        assertEquals(emptyList<String>(), errors(example(FIXED_EXAMPLE)))
        assertEquals(emptyList<String>(), errors(example(MOVING_EXAMPLE)))
    }

    @Test
    fun aFixedCoreS3ItemValidates() {
        assertEquals(emptyList<String>(), errors(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)))
    }

    @Test
    fun aFixedItemNamesItsResolution8Cell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template))
        val code = item["properties"]["grid:code"].asText()
        val cell = code.removePrefix("H3-").toLong(16)
        assertEquals(8, H3Index.resolution(cell))
        assertEquals(OSU_CELL, cell)
        assertEquals(0.01, item["properties"]["anon:size"].asDouble(), 0.0)
        assertEquals("./$FILE_NAME", item["assets"]["data"]["href"].asText())
    }

    @Test
    fun aMovingWaveshareItemValidates() {
        val json = itemJson(movingRows(), Mobility.MOVING, waveshareTemplate)
        assertTrue(json.contains("\"MultiPolygon\""))
        assertFalse(FileItems.GRID_EXTENSION in mapper.readTree(json)["stac_extensions"].map { it.asText() })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test
    fun theSchemasCatchAFixedItemWithoutItsCell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item["properties"] as ObjectNode).remove("grid:code")
        assertTrue(errors(mapper.writeValueAsString(item)).isNotEmpty())
    }

    @Test
    fun theProfileCatchesACellAtAnotherResolution() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item["properties"] as ObjectNode).put("grid:code", "H3-${H3Index.toHex(H3Index.parent(OSU_CELL, 7))}")
        assertTrue(errors(mapper.writeValueAsString(item)).isNotEmpty())
    }

    @Test
    fun theProfileCatchesAMovingItemThatListsGrid() {
        val item = mapper.readTree(itemJson(movingRows(), Mobility.MOVING, waveshareTemplate)) as ObjectNode
        (item["stac_extensions"] as com.fasterxml.jackson.databind.node.ArrayNode).add(FileItems.GRID_EXTENSION)
        assertTrue(errors(mapper.writeValueAsString(item)).isNotEmpty())
    }

    @Test
    fun theCoreSchemaCatchesAnItemWithoutLinks() {
        val json = itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)
        assertEquals(emptyList<String>(), coreErrors(json))
        val item = mapper.readTree(json) as ObjectNode
        item.remove("links")
        assertFalse(coreErrors(mapper.writeValueAsString(item)).isEmpty())
    }

    @Test
    fun aRebuiltFixedItemEqualsTheBoardsExample() {
        val board = mapper.readTree(example(FIXED_EXAMPLE))
        val cell = H3Index.fromHex(board["properties"]["grid:code"].asText().removePrefix("H3-"))
        val rows = List(90) { Row((HOUR_S + 10L * it) * 1_000_000L, cell) }
        assertSameItem(board, rows, Mobility.FIXED)
    }

    @Test
    fun aRebuiltMovingItemEqualsTheBoardsExample() {
        val board = mapper.readTree(example(MOVING_EXAMPLE))
        val ring = board["geometry"]["coordinates"][0].map { LatLng(it[1].asDouble(), it[0].asDouble()) }.dropLast(1)
        val cells = h3.polygonToCells(ring, 8)
        assertTrue("the example covers more than one cell", cells.size > 1)
        // 08:15:00Z, 90 rows 10 s apart, visiting the example's cells in turn.
        val start = HOUR_S - 4 * 3600 - 45 * 60
        val rows = List(90) { Row((start + 10L * it) * 1_000_000L, cells[it % cells.size]) }
        assertSameItem(board, rows, Mobility.MOVING)
    }

    /**
     * The phone Item for a file shaped like the [board] example equals it as JSON values. The example
     * has no Parquet beside it, so only the values read from the bytes, `file:size`, `file:checksum`
     * and `table:columns`, are taken from the phone Item. The geometry and bbox agree within 1e-9
     * degrees, the tolerance of the profile's own check (`scripts/check-profile.mjs`), because h3-js
     * and the phone's H3 differ in the last bits. Everything else is compared exactly.
     */
    private fun assertSameItem(board: JsonNode, rows: List<Row>, mobility: Mobility) {
        val name = board["id"].asText() + ".parquet"
        val phone = mapper.readTree(itemJson(rows, mobility, coreS3Template, name, board["collection"].asText())) as ObjectNode
        val expected = board.deepCopy<ObjectNode>()
        val data = expected["assets"]["data"] as ObjectNode
        for (key in listOf("file:size", "file:checksum", "table:columns")) data.set<JsonNode>(key, phone["assets"]["data"][key])
        for (key in listOf("geometry", "bbox")) {
            assertTrue("$key\n${phone[key]}\ndiffers from the board's\n${expected[key]}", expected[key].equals(SAME_COORDINATES, phone[key]))
        }
        val rest = phone.deepCopy().apply { remove(listOf("geometry", "bbox")) }
        expected.remove(listOf("geometry", "bbox"))
        assertTrue("phone Item\n$rest\ndiffers from the board's\n$expected", expected.equals(SAME_JSON_VALUE, rest))
    }

    private companion object {
        const val CORE_ITEM = "https://schemas.stacspec.org/v1.1.0/item-spec/json-schema/item.json"
        const val STAC_EXTENSIONS = "https://stac-extensions.github.io/"
        const val FIXED_EXAMPLE = "item-fixed.json"
        const val MOVING_EXAMPLE = "item-moving.json"

        /** JSON value equality, where 10 and 10.0 are the same number. */
        val SAME_JSON_VALUE = Comparator<JsonNode> { a, b ->
            when {
                a == b -> 0
                a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue())
                else -> 1
            }
        }

        /** Coordinates that agree within 1e-9 degrees, as `scripts/check-profile.mjs` compares them. */
        val SAME_COORDINATES = Comparator<JsonNode> { a, b ->
            when {
                a == b -> 0
                a.isNumber && b.isNumber && kotlin.math.abs(a.asDouble() - b.asDouble()) < 1e-9 -> 0
                else -> 1
            }
        }
    }
}
