package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test

/**
 * DELTA_BINARY_PACKED and BYTE_STREAM_SPLIT against PyArrow and DuckDB, bit for bit. The fixtures and
 * `parquet/encodings/expected.json` come from `tools/make_encoding_fixtures.py`, which records an FNV-1a
 * 64 digest of every column as the writer's own reader decodes it. This test computes the same digest
 * over [ParquetFile] and over a [ParquetReader] scan, so one flipped bit in any row fails it.
 */
class EncodingFixtureTest {

    private val files: List<JsonObject> by lazy {
        Json.parseToJsonElement(fixtureText("parquet/encodings/expected.json")).jsonObject["files"]!!.jsonArray.map { it.jsonObject }
    }

    private fun bytesOf(f: JsonObject): ByteArray {
        val bytes = fixtureBytes("parquet/" + f["file"]!!.jsonPrimitive.content)
        assertEquals("${f.label} size", f["size"]!!.jsonPrimitive.long, bytes.size.toLong())
        return bytes
    }

    private val JsonObject.label: String get() = this["label"]!!.jsonPrimitive.content

    private fun JsonObject.columns(): Map<String, JsonObject> = this["columns"]!!.jsonObject.mapValues { it.value.jsonObject }

    @Test
    fun `fixtures cover both encodings from both writers on every page version and codec`() {
        val labels = files.map { it.label }
        for (l in listOf("pyarrow-none-v1", "pyarrow-none-v2", "pyarrow-lz4-v1", "pyarrow-lz4-v2", "pyarrow-one-row", "duckdb-uncompressed", "duckdb-snappy")) {
            assertTrue("fixture $l", l in labels)
        }
        for (f in files) {
            val encodings = f.columns().values.flatMap { c -> c["encodings"]!!.jsonArray.map { it.jsonPrimitive.content } }
            assertTrue("${f.label} has DELTA_BINARY_PACKED", "DELTA_BINARY_PACKED" in encodings)
            assertTrue("${f.label} has BYTE_STREAM_SPLIT", "BYTE_STREAM_SPLIT" in encodings)
        }
    }

    @Test
    fun `footer encodings match the writer`() {
        for (f in files) {
            val md = ParquetFile.wrap(bytesOf(f), f.label, testCodecs).metadata
            assertEquals("${f.label} rows", f["rows"]!!.jsonPrimitive.long, md.numRows)
            assertEquals("${f.label} row groups", f["row_groups"]!!.jsonPrimitive.int, md.rowGroups.size)
            for ((name, c) in f.columns()) {
                val leaf = md.leaves.first { it.name == name }
                val want = c["encodings"]!!.jsonArray.map { ParquetEncoding.valueOf(it.jsonPrimitive.content) }.toSet()
                val got = md.rowGroups.flatMap { it.columns[leaf.index].encodings }.toSet()
                assertEquals("${f.label} '$name' encodings", want, got)
                assertEquals("${f.label} '$name' type", c["physical"]!!.jsonPrimitive.content, leaf.type.name)
            }
        }
    }

    @Test
    fun `every column decodes bit for bit as its writer reads it`() {
        for (f in files) {
            val pf = ParquetFile.wrap(bytesOf(f), f.label, testCodecs)
            for ((name, c) in f.columns()) {
                val values = pf.readColumn(name)
                assertEquals("${f.label} '$name' nulls", c["nulls"]!!.jsonPrimitive.int, values.nullCount)
                assertEquals("${f.label} '$name' digest", c["fnv64"]!!.jsonPrimitive.content, digest(values))
            }
        }
    }

    @Test
    fun `a range scan decodes the same bits as a full read`() = runTest {
        for (f in files) {
            val bytes = bytesOf(f)
            val names = f.columns().keys.toList()
            val full = ParquetFile.wrap(bytes, f.label, testCodecs).let { pf -> names.associateWith { pf.readColumn(it) } }
            val seq = full.getValue("seq") as ColumnValues.Int64
            val range = seq.values[seq.size / 3]..seq.values[seq.size / 2]
            val rows = seq.values.indices.filter { seq.values[it] in range }
            ParquetReader.open(MemoryRangeSource(bytes, f.label), testCodecs).use { reader ->
                for (pageIndex in listOf(true, false)) {
                    val batch = reader.scan(ScanRequest(names, filterColumn = "seq", filterRanges = listOf(range), usePageIndex = pageIndex))
                    assertEquals("${f.label} rows", rows.size, batch.rowCount)
                    for (name in names) {
                        assertEquals("${f.label} '$name' page index $pageIndex", digest(full.getValue(name), rows), digest(batch[name]!!.toColumnValues()))
                    }
                }
            }
        }
    }

    @Test
    fun `pyarrow pages are small enough that a range scan skips some`() = runTest {
        val f = files.first { it.label == "pyarrow-none-v2" }
        val bytes = bytesOf(f)
        val seq = ParquetFile.wrap(bytes, f.label, testCodecs).readColumn("seq") as ColumnValues.Int64
        ParquetReader.open(MemoryRangeSource(bytes, f.label), testCodecs).use { reader ->
            val batch = reader.scan(ScanRequest(listOf("seq", "i64_delta", "f64_split"), filterColumn = "seq", filterRanges = listOf(seq.values[100]..seq.values[110])))
            assertEquals(11, batch.rowCount)
            assertTrue("pages read ${batch.pagesRead} of ${batch.pagesTotal}", batch.pagesRead < batch.pagesTotal)
        }
    }

    companion object {
        /** The digest of `tools/make_encoding_fixtures.py`: 0 for a null, else 1 and the value's 64 bits little endian. */
        fun digest(values: ColumnValues, rows: List<Int> = (0 until values.size).toList()): String {
            var h = 0xCBF29CE484222325uL.toLong()
            fun mix(b: Int) {
                h = (h xor (b.toLong() and 0xFF)) * 0x100000001B3L
            }
            for (i in rows) {
                if (values.isNull(i)) {
                    mix(0)
                    continue
                }
                val bits = when (values) {
                    is ColumnValues.Int32 -> values.values[i].toLong()
                    is ColumnValues.Int64 -> values.values[i]
                    is ColumnValues.Float32 -> values.values[i].toRawBits().toLong() and 0xFFFF_FFFFL
                    is ColumnValues.Float64 -> values.values[i].toRawBits()
                    else -> fail("no digest for ${values::class.simpleName}")
                }
                mix(1)
                for (k in 0 until 8) mix((bits ushr (8 * k)).toInt())
            }
            return h.toULong().toString(16).padStart(16, '0')
        }
    }
}
