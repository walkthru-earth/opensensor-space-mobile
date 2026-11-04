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
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.OpenSensorFooter.Mobility
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.H3Index
import kotlin.test.assertIs

/**
 * Items validated against every schema a STAC validator applies to them, with no network.
 *
 * - The stac-opensensor v0.1.0 profile and extension schemas, its fixed and moving example Items, the
 *   row fixtures behind them and the footers they name, copied byte for byte from the v0.1.0 tag
 *   (commit 83787768cc8451109d48a03d4ef5493ffcd403dd) into `src/jvmTest/resources/stac-opensensor`.
 *   The examples validate too, so a failure points at the writer, not at the copy.
 * - The core STAC 1.1.0 Item schema with the GeoJSON Feature and Geometry schemas it references, and
 *   the Grid v1.1.0, Anonymized Location v1.0.0, Table v1.3.0 and File v2.1.0 extension schemas.
 *   They are copied from their `$id` URLs on 2026-11-04 into `src/jvmTest/resources/schemas/`, one
 *   folder per host, with only line endings normalized. Every `https://` reference resolves there.
 *   As in any STAC validator, an extension schema applies to the Items that list it in `stac_extensions`.
 *
 * Items are built at the published resolution, 8, as `PublishGate` builds them for a board. A phone
 * Item rebuilt from the fixture rows of each example equals that example as JSON values under the
 * profile's canonical form, so it equals the board's Item.
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
        assertSameItem(FIXED_EXAMPLE, FIXED_ROWS, "thing=3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13/year=2026/month=10/day=24")
    }

    @Test
    fun aRebuiltMovingItemEqualsTheBoardsExample() {
        // Its first row is 08:17:20, in the 08:15 window the file is named after.
        assertSameItem(MOVING_EXAMPLE, MOVING_ROWS, "thing=7c1e4b9a-3d52-4f86-a0b7-e9d2c5f81a46/year=2026/month=10/day=24")
    }

    /**
     * The phone Item for the profile's row fixture [rowsName] equals the board's example [exampleName]
     * as JSON values, the canonical form of PROFILE.md, and sits at the example's path under [partition].
     * The fixture rows are written to a Parquet file with the footer and bundled template they name. Only
     * `file:size` and `file:checksum` come from the phone Item, since they hash the phone's own bytes,
     * not the board's. The geometry and bbox agree within 1e-9 degrees, the tolerance of
     * `scripts/canonical.mjs`, because H3 builds differ in the last bits. Everything else is exact.
     */
    private fun assertSameItem(exampleName: String, rowsName: String, partition: String) {
        val board = mapper.readTree(example(exampleName))
        val fixture = mapper.readTree(example(rowsName))
        val footer = checkNotNull(OpenSensorFooter.parse(mapper.writeValueAsString(mapper.readTree(example(fixture["footer"].asText())))))
        val template = checkNotNull(BundledCatalog.template(checkNotNull(footer.dictionarySha256)))
        val name = fixture["file"]["name"].asText()
        val result = FileItems.build(name, ByteArrayReader(fixtureFile(fixture["rows"], template)), footer, template, PUBLISHED_RES, h3)
        val item = assertIs<FileItemResult.Item>(result)
        assertEquals("$partition/${board["id"].asText()}.json", item.sidecarPath)
        val phone = mapper.readTree(item.json) as ObjectNode
        val expected = board.deepCopy<ObjectNode>()
        val data = expected["assets"]["data"] as ObjectNode
        for (key in listOf("file:size", "file:checksum")) data.set<JsonNode>(key, phone["assets"]["data"][key])
        for (key in listOf("geometry", "bbox")) {
            assertTrue("$key\n${phone[key]}\ndiffers from the board's\n${expected[key]}", expected[key].equals(SAME_COORDINATES, phone[key]))
        }
        val rest = phone.deepCopy().apply { remove(listOf("geometry", "bbox")) }
        expected.remove(listOf("geometry", "bbox"))
        assertTrue("phone Item\n$rest\ndiffers from the board's\n$expected", expected.equals(SAME_JSON_VALUE, rest))
        assertEquals(emptyList<String>(), errors(item.json))
    }

    /**
     * A Parquet file of the fixture [rows], a column name to its values with null for a null cell.
     * Each column takes the Parquet type of its template column, `h3_cell_id` is the int64 of its hex
     * and the time is int64 microseconds, as the board writes them.
     */
    private fun fixtureFile(rows: JsonNode, template: space.opensensor.model.BoardTemplate): ByteArray {
        val names = rows.fieldNames().asSequence().toList()
        val size = rows[names.first()].size()
        val columns = names.map { name ->
            val type = template.datastream(name)?.type
            when {
                name == UtcColumns.EVENT_US -> ParquetWriter.Column(name, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS)
                name == FileItems.H3_CELL_COLUMN || type == "int64" || type == "uint64" -> ParquetWriter.Column(name, ParquetType.INT64)
                type == "float" -> ParquetWriter.Column(name, ParquetType.FLOAT)
                type == "double" -> ParquetWriter.Column(name, ParquetType.DOUBLE)
                else -> ParquetWriter.Column(name, ParquetType.INT32)
            }
        }
        val values = columns.map { column ->
            val cells = rows[column.name]
            val valid = BooleanArray(size) { !cells[it].isNull }
            when {
                column.name == FileItems.H3_CELL_COLUMN -> ColumnValues.Int64(LongArray(size) { if (valid[it]) H3Index.fromHex(cells[it].asText()) else 0L }, valid)
                column.type == ParquetType.INT64 -> ColumnValues.Int64(LongArray(size) { cells[it].asLong() }, valid)
                column.type == ParquetType.FLOAT -> ColumnValues.Float32(FloatArray(size) { cells[it].asDouble().toFloat() }, valid)
                column.type == ParquetType.DOUBLE -> ColumnValues.Float64(DoubleArray(size) { cells[it].asDouble() }, valid)
                else -> ColumnValues.Int32(IntArray(size) { cells[it].asInt() }, valid)
            }
        }
        val out = MemorySink()
        val writer = ParquetWriter(out, columns, ParquetCodec.UNCOMPRESSED)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return out.toByteArray()
    }

    private companion object {
        const val CORE_ITEM = "https://schemas.stacspec.org/v1.1.0/item-spec/json-schema/item.json"
        const val STAC_EXTENSIONS = "https://stac-extensions.github.io/"
        const val FIXED_EXAMPLE = "item-fixed.json"
        const val MOVING_EXAMPLE = "item-moving.json"
        const val FIXED_ROWS = "rows-fixed.json"
        const val MOVING_ROWS = "rows-moving.json"

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
