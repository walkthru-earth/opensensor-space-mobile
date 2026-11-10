package space.opensensor.stac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.H3Index
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A phone Item rebuilt from each row fixture of stac-opensensor v0.1.0 (tag v0.1.0, commit
 * 83787768cc8451109d48a03d4ef5493ffcd403dd) equals the board's example Item as JSON values, the
 * canonical form of PROFILE.md. It runs on the JVM and on iOS, so both H3 bindings and both ways of
 * printing a double are checked against the examples. The JVM test also validates the rebuilt Items
 * against the schemas.
 */
class ProfileExampleTest {
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
     * The phone Item for the row fixture [rowsName] equals the board's example [exampleName] as JSON
     * values and sits at the example's path under [partition]. Only `file:size` and `file:checksum`
     * come from the phone Item, since they hash the phone's own bytes, not the board's. The comparison
     * is [CanonicalJson.difference], exact but for 1e-9 degrees in the geometry and bbox.
     */
    private fun assertSameItem(exampleName: String, rowsName: String, partition: String) {
        val board = Json.parseToJsonElement(stacFile(exampleName).decodeToString()).jsonObject
        val item = assertIs<FileItemResult.Item>(rebuildExample(rowsName))
        assertEquals("$partition/${board["id"]!!.jsonPrimitive.content}.json", item.sidecarPath)
        val phone = Json.parseToJsonElement(item.json).jsonObject
        val phoneData = phone["assets"]!!.jsonObject["data"]!!.jsonObject
        val boardAssets = board["assets"]!!.jsonObject
        val data = JsonObject(boardAssets["data"]!!.jsonObject + phoneData.filterKeys { it == "file:size" || it == "file:checksum" })
        val expected = JsonObject(board + ("assets" to JsonObject(boardAssets + ("data" to data))))
        val at = CanonicalJson.difference(expected, phone)
        assertNull(at, "phone Item differs at $at\n$phone\nfrom the board's\n$expected")
    }
}

const val FIXED_EXAMPLE = "item-fixed.json"
const val MOVING_EXAMPLE = "item-moving.json"
const val FIXED_ROWS = "rows-fixed.json"
const val MOVING_ROWS = "rows-moving.json"

/**
 * The phone's result for the row fixture [rowsName]. The rows are written to a Parquet file with the
 * footer and the bundled template the fixture names, then built as `PublishGate` rebuilds a board
 * file to check its sidecar.
 */
fun rebuildExample(rowsName: String): FileItemResult {
    val fixture = Json.parseToJsonElement(stacFile(rowsName).decodeToString()).jsonObject
    val footer = checkNotNull(OpenSensorFooter.parse(stacFile(fixture["footer"]!!.jsonPrimitive.content).decodeToString()))
    val template = checkNotNull(BundledCatalog.template(checkNotNull(footer.dictionarySha256)))
    val name = fixture["file"]!!.jsonObject["name"]!!.jsonPrimitive.content
    val bytes = fixtureFile(fixture["rows"]!!.jsonObject, template)
    return FileItems.build(name, ByteArrayReader(bytes), footer, template, PUBLISHED_RES, h3)
}

/**
 * A Parquet file of the fixture [rows], a column name to its values with null for a null cell. Each
 * column takes the Parquet type of its template column, `h3_cell_id` is the int64 of its hex and the
 * time is int64 microseconds, as the board writes them.
 */
private fun fixtureFile(rows: JsonObject, template: BoardTemplate): ByteArray {
    val size = rows.values.first().jsonArray.size
    val columns = rows.keys.map { name ->
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
        val cells = rows[column.name]!!.jsonArray.map { it.jsonPrimitive }
        val valid = BooleanArray(size) { cells[it] !is JsonNull }
        when {
            column.name == FileItems.H3_CELL_COLUMN -> ColumnValues.Int64(LongArray(size) { if (valid[it]) H3Index.fromHex(cells[it].content) else 0L }, valid)
            column.type == ParquetType.INT64 -> ColumnValues.Int64(LongArray(size) { cells[it].longOrNull ?: 0L }, valid)
            column.type == ParquetType.FLOAT -> ColumnValues.Float32(FloatArray(size) { cells[it].doubleOrNull?.toFloat() ?: 0f }, valid)
            column.type == ParquetType.DOUBLE -> ColumnValues.Float64(DoubleArray(size) { cells[it].doubleOrNull ?: 0.0 }, valid)
            else -> ColumnValues.Int32(IntArray(size) { cells[it].intOrNull ?: 0 }, valid)
        }
    }
    val out = MemorySink()
    val writer = ParquetWriter(out, columns, ParquetCodec.UNCOMPRESSED)
    writer.begin()
    writer.rowGroup(values)
    writer.finish()
    return out.toByteArray()
}
