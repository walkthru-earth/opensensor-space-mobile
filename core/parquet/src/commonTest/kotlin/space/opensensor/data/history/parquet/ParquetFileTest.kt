package space.opensensor.data.history.parquet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

/**
 * The reader against PyArrow as the oracle. `src/test/resources/parquet/expected.json` is produced by
 * `tools/make_parquet_fixtures.py` (run in the firmware repo's pixi environment) from nine *real*
 * device files (v1 73-column and v2 77-column, UNCOMPRESSED and LZ4_RAW, dated and unsynced, 1–90
 * rows) and twelve firmware-writer v3 fixtures (up to 8 row groups, statistics, TIMESTAMP(NANOS, UTC),
 * 0 rows, 65 k-free "hourly" 4 × 90). Every column of every file is decoded and compared on null
 * count, first/last, min/max and sum; every footer fact PyArrow reports is compared too.
 */
class ParquetFileTest {

    private val expected: JsonObject by lazy {
        val text = fixtureText("parquet/expected.json")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun fixtures(): List<JsonObject> = expected["files"]!!.jsonArray.map { it.jsonObject }

    private fun openFixture(f: JsonObject): ParquetFile {
        val bytes = fixtureBytes("parquet/" + f["file"]!!.jsonPrimitive.content)
        assertEquals("fixture size", f["size"]!!.jsonPrimitive.long, bytes.size.toLong())
        return ParquetFile.wrap(bytes, f["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fixtures cover both writers and both codecs with one and many row groups`() {
        val labels = fixtures().map { it["label"]!!.jsonPrimitive.content }
        assertTrue(labels.any { it.startsWith("v1-lz4") } && labels.any { it.startsWith("v1-none") })
        assertTrue(labels.any { it.startsWith("v2-") } && labels.any { it.startsWith("v3-hourly") } && labels.any { it.startsWith("v3-max-groups") })
        assertTrue(labels.size >= 21)
    }

    @Test
    fun `footer facts match PyArrow for every fixture`() {
        for (f in fixtures()) {
            val label = f["label"]!!.jsonPrimitive.content
            val pf = openFixture(f)
            val md = pf.metadata
            assertEquals("$label rows", f["rows"]!!.jsonPrimitive.long, md.numRows)
            assertEquals("$label columns", f["columns"]!!.jsonPrimitive.int, md.leaves.size)
            assertEquals("$label created_by", f["created_by"]!!.jsonPrimitive.content, md.createdBy)
            val groups = f["row_groups"]!!.jsonArray
            assertEquals("$label row groups", groups.size, md.rowGroups.size)
            groups.forEachIndexed { g, gj ->
                val rg = md.rowGroups[g]
                val o = gj.jsonObject
                assertEquals("$label rg$g rows", o["rows"]!!.jsonPrimitive.long, rg.numRows)
                assertEquals("$label rg$g bytes", o["compressed"]!!.jsonPrimitive.long, rg.totalByteSize)
                val codec = when (o["codec"]!!.jsonPrimitive.content) {
                    "LZ4" -> ParquetCodec.LZ4_RAW // PyArrow's name for codec 7
                    else -> ParquetCodec.UNCOMPRESSED
                }
                rg.columns.forEach { c -> assertEquals("$label rg$g codec", codec, c.codec) }
                assertEquals("$label rg$g encodings", listOf(ParquetEncoding.PLAIN, ParquetEncoding.RLE), rg.columns[0].encodings.sortedBy { it.code })
                // Statistics: every (column → min/max/nulls) PyArrow saw must be in our footer model, decoded identically.
                val stats = o["stats"]!!.jsonObject
                for ((column, sj) in stats) {
                    val leaf = pf.leaf(column)
                    val s = rg.columns[leaf.index].statistics
                    assertNotNull("$label rg$g '$column' statistics", s)
                    val so = sj.jsonObject
                    assertEquals("$label rg$g '$column' null_count", so["nulls"]!!.jsonPrimitive.long, s!!.nullCount)
                    assertTrue("$label rg$g '$column' has min/max", s.hasMinMax)
                    when (leaf.type) {
                        ParquetType.FLOAT -> {
                            so["min"]?.let { assertEquals("$label '$column' min", it.jsonPrimitive.double, s.minFloat()!!.toDouble(), 0.0) }
                            so["max"]?.let { assertEquals("$label '$column' max", it.jsonPrimitive.double, s.maxFloat()!!.toDouble(), 0.0) }
                        }
                        else -> {
                            assertEquals("$label '$column' min", so["min"]!!.jsonPrimitive.long, s.minLong(leaf.type))
                            assertEquals("$label '$column' max", so["max"]!!.jsonPrimitive.long, s.maxLong(leaf.type))
                        }
                    }
                }
                // And the reverse: we never invent min/max PyArrow did not see.
                rg.columns.forEach { c -> if (c.statistics?.hasMinMax == true) assertTrue("$label rg$g '${c.name}' unexpected min/max", stats.containsKey(c.name)) }
            }
            // Key/value metadata, byte-exact.
            val kv = f["metadata"]!!.jsonObject
            assertEquals("$label metadata keys", kv.keys, md.keyValueMetadata.keys)
            kv.forEach { (k, v) -> assertEquals("$label metadata[$k]", v.jsonPrimitive.content, md.keyValueMetadata[k]) }
            // Columns: names, physical types, and the TIMESTAMP annotation exactly where PyArrow sees timestamp[ns, tz=UTC].
            val cols = f["column_details"]!!.jsonArray.map { it.jsonObject }
            cols.forEachIndexed { i, cj ->
                val leaf = md.leaves[i]
                assertEquals("$label column $i name", cj["name"]!!.jsonPrimitive.content, leaf.name)
                assertEquals("$label '${leaf.name}' physical", cj["physical"]!!.jsonPrimitive.content, leaf.type.name)
                val arrow = cj["arrow"]!!.jsonPrimitive.content
                assertEquals("$label '${leaf.name}' timestamp annotation", arrow == "timestamp[ns, tz=UTC]", leaf.timestampUtcUnit == ParquetLogicalType.TimeUnit.NANOS)
            }
        }
    }

    @Test
    fun `every column of every fixture decodes to the PyArrow values`() {
        var columnsChecked = 0
        for (f in fixtures()) {
            val label = f["label"]!!.jsonPrimitive.content
            val pf = openFixture(f)
            val cols = f["column_details"]!!.jsonArray.map { it.jsonObject }
            for (cj in cols) {
                val name = cj["name"]!!.jsonPrimitive.content
                val col = pf.readColumn(name)
                val ctx = "$label '$name'"
                assertEquals("$ctx size", f["rows"]!!.jsonPrimitive.int, col.size)
                assertEquals("$ctx nulls", cj["nulls"]!!.jsonPrimitive.int, col.nullCount)
                val valid = cj["valid"]!!.jsonPrimitive.int
                assertEquals("$ctx valid", valid, col.size - col.nullCount)
                if (valid == 0) continue
                val indices = (0 until col.size).filter { !col.isNull(it) }
                when (col) {
                    is ColumnValues.Float32 -> {
                        val values = indices.map { col.values[it] }
                        assertEquals("$ctx NaN count", cj["nan"]!!.jsonPrimitive.int, values.count { it.isNaN() })
                        assertEquals("$ctx inf count", cj["inf"]!!.jsonPrimitive.int, values.count { it.isInfinite() })
                        cj["first"]?.let { assertEquals("$ctx first", it.jsonPrimitive.double, values.first().toDouble(), 0.0) }
                        cj["last"]?.let { assertEquals("$ctx last", it.jsonPrimitive.double, values.last().toDouble(), 0.0) }
                        val finite = values.filter { it.isFinite() }.map { it.toDouble() }
                        if (finite.isNotEmpty()) {
                            assertEquals("$ctx min", cj["min"]!!.jsonPrimitive.double, finite.min(), 0.0)
                            assertEquals("$ctx max", cj["max"]!!.jsonPrimitive.double, finite.max(), 0.0)
                            val sum = cj["sum"]!!.jsonPrimitive.double
                            assertEquals("$ctx sum", sum, finite.sum(), max(1e-6, abs(sum) * 1e-9))
                        }
                    }
                    else -> {
                        val values = indices.map { col.asLong(it)!! }
                        assertEquals("$ctx first", cj["first"]!!.jsonPrimitive.long, values.first())
                        assertEquals("$ctx last", cj["last"]!!.jsonPrimitive.long, values.last())
                        assertEquals("$ctx min", cj["min"]!!.jsonPrimitive.long, values.min())
                        assertEquals("$ctx max", cj["max"]!!.jsonPrimitive.long, values.max())
                        // Sums of INT64 boundary fixtures overflow a Long in Python's arbitrary precision; compare modulo 2^64.
                        val sum = decimalMod64(cj["sum"]!!.jsonPrimitive.content)
                        assertEquals("$ctx sum", sum, values.fold(0L) { a, b -> a + b })
                    }
                }
                columnsChecked++
            }
        }
        assertTrue("checked $columnsChecked columns", columnsChecked > 800)
    }

    @Test
    fun `row group reads concatenate to the whole column and match per group statistics`() {
        val f = fixtures().first { it["label"]!!.jsonPrimitive.content == "v3-hourly-lz4" }
        val pf = openFixture(f)
        assertEquals(4, pf.rowGroupCount)
        val time = pf.leaf("time_ms")
        assertEquals(ParquetLogicalType.TimeUnit.NANOS, time.timestampUtcUnit)
        val whole = pf.readColumn(time) as ColumnValues.Int64
        var offset = 0
        for (g in 0 until pf.rowGroupCount) {
            val part = pf.readColumn(time, g) as ColumnValues.Int64
            val stats = pf.metadata.rowGroups[g].columns[time.index].statistics!!
            assertEquals("rg$g min from statistics", stats.minLong(ParquetType.INT64), part.values.min())
            assertEquals("rg$g max from statistics", stats.maxLong(ParquetType.INT64), part.values.max())
            assertTrue(part.values.contentEquals(whole.values.copyOfRange(offset, offset + part.size)))
            offset += part.size
        }
        assertEquals(whole.size, offset)
        // The v6 writer records the sort order; whatever column it names must exist.
        pf.metadata.rowGroups[0].sortingColumns.forEach { assertTrue(it.columnIdx in pf.leaves.indices) }
    }

    @Test
    fun `real device file with unsynced rows has null UTC and a monotonic clock`() {
        val f = fixtures().first { it["label"]!!.jsonPrimitive.content == "v2-none-90rows-unsynced" }
        val pf = openFixture(f)
        val utc = pf.readColumn("event_time_utc_ns")
        assertEquals(90, utc.nullCount)
        val mono = pf.readColumn("monotonic_us") as ColumnValues.Int64
        assertEquals(0, mono.nullCount)
        for (i in 1 until mono.size) assertTrue("monotonic increases", mono.values[i] > mono.values[i - 1])
        // Only three of 77 column chunks were touched for this: projection is the point of the reader.
        // PMS concentrations are integers on the wire; the board temperature is the FLOAT.
        assertTrue(pf.readColumn("pm25_atmospheric_ug_m3") is ColumnValues.Int32)
        assertTrue(pf.readColumn("imu_temperature_c") is ColumnValues.Float32)
    }

    @Test
    fun `empty file has zero rows and empty columns`() {
        val f = fixtures().first { it["label"]!!.jsonPrimitive.content == "v3-empty-lz4" }
        val pf = openFixture(f)
        assertEquals(0L, pf.numRows)
        assertEquals(0, pf.rowGroupCount)
        assertEquals(0, pf.readColumn("time_ms").size)
    }

    @Test
    fun `malformed input is refused with a typed exception`() {
        val f = fixtures().first { it["label"]!!.jsonPrimitive.content == "v1-none-1row-dated" }
        val bytes = fixtureBytes("parquet/" + f["file"]!!.jsonPrimitive.content)
        // Truncated footer.
        try {
            ParquetFile.wrap(bytes.copyOf(bytes.size - 20))
            fail("truncated file accepted")
        } catch (e: ParquetFormatException) {
            assertNotNull(e.message)
        }
        // Footer length pointing outside the file.
        val bad = bytes.copyOf()
        bad[bad.size - 8] = 0x7F
        bad[bad.size - 7] = 0x7F
        bad[bad.size - 6] = 0x7F
        bad[bad.size - 5] = 0x7F
        try {
            ParquetFile.wrap(bad)
            fail("out-of-range footer accepted")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message!!.contains("footer length"))
        }
        // Unknown column.
        try {
            ParquetFile.wrap(bytes).readColumn("no_such_column")
            fail("unknown column accepted")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message!!.contains("no_such_column"))
        }
        assertNull(ParquetFile.wrap(bytes).metadata.leaf("no_such_column"))
    }
}
