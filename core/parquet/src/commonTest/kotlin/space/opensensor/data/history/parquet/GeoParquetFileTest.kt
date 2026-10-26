package space.opensensor.data.history.parquet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test

/**
 * GeoParquet 2.0 against PyArrow 25 and DuckDB 1.5.5 as oracles (docs/parquet.md, Fixtures and oracles).
 * `src/test/resources/geoparquet/expected.json` is produced by `tools/make_geoparquet_fixtures.py`: 18
 * PyArrow files (codec × dictionary × data-page version, a multi-page/multi-group one whose dictionary
 * overflows into PLAIN pages, one without the `geo` key) and two DuckDB files (`GEOPARQUET_VERSION` V2
 * and V1) over the same rows: all seven OGC types, XY/XYZ/XYZM, both WKB byte orders, an empty
 * geometry, nulls, a duplicate. Every column of every file is decoded and compared; the geometry column
 * is compared as WKT against DuckDB `ST_AsText`, and the footer's geospatial statistics against PyArrow.
 */
class GeoParquetFileTest {

    private val expected: JsonObject by lazy {
        val text = fixtureText("geoparquet/expected.json")
        Json.parseToJsonElement(text).jsonObject
    }

    /** Every fixture, for the oracle and coverage checks. */
    private fun allFixtures(): List<JsonObject> = expected["files"]!!.jsonArray.map { it.jsonObject }

    /** The fixtures this platform can decode: all of them on the JVM, all but ZSTD on iOS. */
    private fun fixtures(): List<JsonObject> = allFixtures().filter { codecs.supports(ParquetCodec.ZSTD) || !label(it).contains("zstd") }

    private fun label(f: JsonObject) = f["label"]!!.jsonPrimitive.content

    private fun openFixture(f: JsonObject): ParquetFile {
        val bytes = fixtureBytes("geoparquet/" + f["file"]!!.jsonPrimitive.content)
        assertEquals("fixture size", f["size"]!!.jsonPrimitive.long, bytes.size.toLong())
        return ParquetFile.wrap(bytes, label(f), codecs)
    }

    /** Built-ins plus ZSTD on the JVM (zstd-jni, as `:app` registers it); iOS skips the ZSTD fixtures. */
    private val codecs = testCodecs

    private fun codecOf(name: String) = when (name) {
        "UNCOMPRESSED" -> ParquetCodec.UNCOMPRESSED
        "SNAPPY" -> ParquetCodec.SNAPPY
        "GZIP" -> ParquetCodec.GZIP
        "ZSTD" -> ParquetCodec.ZSTD
        "LZ4" -> ParquetCodec.LZ4_RAW
        else -> fail("unknown codec $name")
    }

    @Test
    fun `fixtures cover every codec and encoding and page version and both writers`() {
        val labels = allFixtures().map(::label)
        for (codec in listOf("none", "snappy", "gzip", "zstd")) {
            for (enc in listOf("plain", "dict")) {
                for (v in listOf("v1", "v2")) {
                    assertTrue("pa-$codec-$enc-$v", "pa-$codec-$enc-$v" in labels)
                }
            }
        }
        assertTrue("pa-snappy-multipage" in labels && "pa-native-only" in labels && "duckdb-v2" in labels && "duckdb-v1" in labels)
    }

    @Test
    fun `footer facts and geospatial statistics match the oracle for every fixture`() {
        for (f in fixtures()) {
            val label = label(f)
            val pf = openFixture(f)
            val md = pf.metadata
            assertEquals("$label rows", f["rows"]!!.jsonPrimitive.long, md.numRows)
            assertEquals("$label columns", f["columns"]!!.jsonPrimitive.int, md.leaves.size)
            assertEquals("$label created_by", f["created_by"]!!.jsonPrimitive.content, md.createdBy)
            val kv = f["metadata"]!!.jsonObject
            assertEquals("$label geo key present", kv.containsKey("geo"), md.keyValueMetadata.containsKey("geo"))
            val groups = f["row_groups"]!!.jsonArray
            assertEquals("$label row groups", groups.size, md.rowGroups.size)
            groups.forEachIndexed { g, gj ->
                val rg = md.rowGroups[g]
                val o = gj.jsonObject
                assertEquals("$label rg$g rows", o["rows"]!!.jsonPrimitive.long, rg.numRows)
                for ((column, cj) in o["chunks"]!!.jsonObject) {
                    val c = cj.jsonObject
                    val chunk = rg.columns[pf.leaf(column).index]
                    assertEquals("$label rg$g $column codec", codecOf(c["codec"]!!.jsonPrimitive.content), chunk.codec)
                    assertEquals("$label rg$g $column values", c["num_values"]!!.jsonPrimitive.long, chunk.numValues)
                    assertEquals("$label rg$g $column dictionary page", c["dictionary_page"]!!.jsonPrimitive.booleanOrNull, chunk.dictionaryPageOffset != null && chunk.dictionaryPageOffset > 0)
                    val encodings = c["encodings"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                    assertEquals("$label rg$g $column encodings", encodings, chunk.encodings.map { it.name }.sorted())
                    c["nulls"]?.jsonPrimitive?.long?.let { assertEquals("$label rg$g $column nulls", it, chunk.statistics?.nullCount) }
                    val geo = c["geo"]?.jsonObject
                    if (geo == null) {
                        assertNull("$label rg$g $column no geo stats", chunk.geospatialStatistics)
                    } else {
                        val gs = checkNotNull(chunk.geospatialStatistics)
                        assertEquals("$label rg$g types", geo["types"]!!.jsonArray.map { it.jsonPrimitive.int }, gs.geospatialTypes)
                        val box = checkNotNull(gs.bbox)
                        assertEquals("$label rg$g xmin", geo["xmin"]!!.jsonPrimitive.double, box.xmin, 0.0)
                        assertEquals("$label rg$g xmax", geo["xmax"]!!.jsonPrimitive.double, box.xmax, 0.0)
                        assertEquals("$label rg$g ymin", geo["ymin"]!!.jsonPrimitive.double, box.ymin, 0.0)
                        assertEquals("$label rg$g ymax", geo["ymax"]!!.jsonPrimitive.double, box.ymax, 0.0)
                        assertEquals("$label rg$g zmin", geo["zmin"]!!.doubleOrNullish(), box.zmin)
                        assertEquals("$label rg$g zmax", geo["zmax"]!!.doubleOrNullish(), box.zmax)
                        assertEquals("$label rg$g mmin", geo["mmin"]!!.doubleOrNullish(), box.mmin)
                        assertEquals("$label rg$g mmax", geo["mmax"]!!.doubleOrNullish(), box.mmax)
                    }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement.doubleOrNullish(): Double? = if (this is JsonNull) null else jsonPrimitive.doubleOrNull

    @Test
    fun `every column of every fixture decodes to the oracle values`() {
        for (f in fixtures()) {
            val label = label(f)
            val pf = openFixture(f)
            for (cj in f["column_details"]!!.jsonArray) {
                val c = cj.jsonObject
                val name = c["name"]!!.jsonPrimitive.content
                val leaf = pf.leaf(name)
                assertEquals("$label $name physical", c["physical"]!!.jsonPrimitive.content, leaf.type.name)
                val values = pf.readColumn(leaf)
                assertEquals("$label $name size", pf.numRows.toInt(), values.size)
                assertEquals("$label $name nulls", c["nulls"]!!.jsonPrimitive.int, values.nullCount)
                val wkt = c["wkt"]?.jsonArray
                val strings = c["values"]?.jsonArray
                when {
                    wkt != null -> {
                        val bin = values as ColumnValues.Binary
                        val decoded = Wkb.decodeColumn(bin)
                        wkt.forEachIndexed { i, w ->
                            val exp = if (w is JsonNull) null else w.jsonPrimitive.content
                            assertEquals("$label $name row $i", exp, decoded[i]?.toWkt())
                        }
                        assertTrue("$label $name logical", c["logical"]!!.jsonPrimitive.content.startsWith("Geometry") || label == "duckdb-v1")
                    }
                    strings != null -> {
                        val bin = values as ColumnValues.Binary
                        assertEquals("$label $name logical", ParquetLogicalType.Utf8, leaf.logicalType)
                        strings.forEachIndexed { i, s -> assertEquals("$label $name row $i", if (s is JsonNull) null else s.jsonPrimitive.content, bin.string(i)) }
                    }
                    else -> {
                        val defined = (0 until values.size).filter { !values.isNull(it) }
                        assertEquals("$label $name valid", c["valid"]!!.jsonPrimitive.int, defined.size)
                        if (defined.isEmpty()) continue
                        val doubles = defined.map { values.asDouble(it)!! }
                        assertEquals("$label $name first", c["first"]!!.jsonPrimitive.double, doubles.first(), 0.0)
                        assertEquals("$label $name last", c["last"]!!.jsonPrimitive.double, doubles.last(), 0.0)
                        assertEquals("$label $name min", c["min"]!!.jsonPrimitive.double, doubles.min(), 0.0)
                        assertEquals("$label $name max", c["max"]!!.jsonPrimitive.double, doubles.max(), 0.0)
                        val sum = c["sum"]!!.jsonPrimitive.double
                        assertTrue("$label $name sum $sum vs ${doubles.sum()}", abs(sum - doubles.sum()) <= 1e-9 * maxOf(1.0, abs(sum)))
                    }
                }
            }
        }
    }

    @Test
    fun `geometry columns are discovered from the logical type and from the geo key alike`() {
        for (f in fixtures()) {
            val label = label(f)
            val pf = openFixture(f)
            val cols = pf.geometryColumns
            assertEquals("$label one geometry column", listOf("geometry"), cols.map { it.name })
            val col = cols.single()
            assertTrue("$label primary", col.primary)
            assertEquals("$label crs", Crs.LonLatWgs84, col.crs)
            assertEquals("$label edges", "planar", col.edges)
            val hasGeo = f["metadata"]!!.jsonObject.containsKey("geo")
            if (hasGeo) {
                val geo = checkNotNull(pf.metadata.geo)
                assertEquals("$label primary_column", "geometry", geo.primaryColumn)
                assertEquals("$label encoding", "WKB", geo.columns["geometry"]!!.encoding)
                assertEquals("$label major", if (label == "duckdb-v1") 1 else 2, geo.majorVersion)
                assertNotNull("$label geometry_types", col.geometryTypes)
                assertNotNull("$label geo bbox", col.geoBbox)
            } else {
                assertNull(pf.metadata.geo)
                assertNull(col.geometryTypes)
            }
            if (label == "duckdb-v1") {
                assertNull("$label 1.x has no logical type", col.leaf.logicalType)
                assertTrue(col.statistics.isEmpty())
            } else {
                assertTrue("$label logical type", col.leaf.logicalType is ParquetLogicalType.Geometry)
                assertEquals("$label stats per row group", pf.rowGroupCount, col.statistics.size)
                // The decoded geometries' own bbox equals the footer's.
                val decoded = pf.readGeometry(col)
                val union = decoded.mapNotNull { it?.bbox() }.reduce { a, b -> a.union(b) }
                assertEquals("$label bbox", col.statisticsBbox, union)
                assertEquals("$label types", col.statisticsTypes, decoded.mapNotNull { it?.wkbCode }.distinct().sorted())
            }
        }
    }

    @Test
    fun `the multi-page fixture really exercises dictionary pages falling back to PLAIN`() {
        val f = fixtures().single { label(it) == "duckdb-v2" }
        assertEquals(1, f["row_groups"]!!.jsonArray.size)
        val mp = fixtures().single { label(it) == "pa-snappy-multipage" }
        assertEquals(2, mp["row_groups"]!!.jsonArray.size)
        // Both encodings on the geometry chunk of row group 0, i.e. a dictionary page, dictionary-indexed pages and a PLAIN page.
        val enc = mp["row_groups"]!!.jsonArray[0].jsonObject["chunks"]!!.jsonObject["geometry"]!!.jsonObject["encodings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(enc.containsAll(listOf("PLAIN", "RLE_DICTIONARY")))
    }

    @Test
    fun `ZSTD is refused with a clear message when no decompressor is registered`() {
        val f = allFixtures().single { label(it) == "pa-zstd-plain-v1" }
        val bytes = fixtureBytes("geoparquet/" + f["file"]!!.jsonPrimitive.content)
        val pf = ParquetFile.wrap(bytes, "zstd", ParquetCodecs.builtIn)
        try {
            pf.readColumn("id")
            fail("expected a ParquetFormatException")
        } catch (e: ParquetFormatException) {
            assertTrue(e.message, e.message!!.contains("ZSTD") && e.message!!.contains("not supported"))
        }
    }

    // ---------------------------------------------------------------- writer round trip

    @Test
    fun `our writer produces GeoParquet 2 that we read back with the oracle statistics`() {
        val f = fixtures().single { label(it) == "pa-none-plain-v1" }
        val src = openFixture(f)
        val id = src.readColumn("id")
        val name = src.readColumn("name")
        val ratio = src.readColumn("ratio")
        val geometry = src.readColumn("geometry")
        val out = MemorySink()
        val w = ParquetWriter(
            out,
            listOf(
                ParquetWriter.Column("id", ParquetType.INT32),
                ParquetWriter.Column("name", ParquetType.BYTE_ARRAY, utf8 = true),
                ParquetWriter.Column("ratio", ParquetType.DOUBLE),
                ParquetWriter.Column("geometry", ParquetType.BYTE_ARRAY, geometry = ParquetWriter.GeometryAnnotation()),
            ),
            codec = ParquetCodec.LZ4_RAW,
            keyValueMetadata = mapOf("writer_test" to "round-trip"),
        )
        w.begin()
        // Two row groups: rows 0..6 and 7..13, so the file-level bbox is a union.
        val split = 7
        w.rowGroup(listOf(id, name, ratio, geometry).map { slice(it, 0, split) })
        w.rowGroup(listOf(id, name, ratio, geometry).map { slice(it, split, it.size) })
        w.finish()

        val pf = ParquetFile.wrap(out.toByteArray(), "round-trip")
        assertEquals(2, pf.rowGroupCount)
        val col = pf.geometryColumns.single()
        assertTrue(col.leaf.logicalType is ParquetLogicalType.Geometry)
        assertEquals(Crs.LonLatWgs84, col.crs)
        val geo = checkNotNull(pf.metadata.geo)
        assertEquals(GeoParquetMetadata.VERSION_2, geo.version)
        assertEquals("geometry", geo.primaryColumn)
        assertEquals("WKB", geo.columns["geometry"]!!.encoding)
        // Same geometries, same WKT.
        val expectedWkt = f["column_details"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "geometry" }["wkt"]!!.jsonArray
        val decoded = pf.readGeometry(col)
        expectedWkt.forEachIndexed { i, w -> assertEquals("row $i", if (w is JsonNull) null else w.jsonPrimitive.content, decoded[i]?.toWkt()) }
        // Same file-level statistics as PyArrow computed for the single-group original.
        val oracle = f["row_groups"]!!.jsonArray[0].jsonObject["chunks"]!!.jsonObject["geometry"]!!.jsonObject["geo"]!!.jsonObject
        val union = checkNotNull(col.statisticsBbox)
        assertEquals(oracle["xmin"]!!.jsonPrimitive.double, union.xmin, 0.0)
        assertEquals(oracle["xmax"]!!.jsonPrimitive.double, union.xmax, 0.0)
        assertEquals(oracle["ymin"]!!.jsonPrimitive.double, union.ymin, 0.0)
        assertEquals(oracle["ymax"]!!.jsonPrimitive.double, union.ymax, 0.0)
        assertEquals(oracle["zmin"]!!.doubleOrNullish(), union.zmin)
        assertEquals(oracle["mmax"]!!.doubleOrNullish(), union.mmax)
        assertEquals(oracle["types"]!!.jsonArray.map { it.jsonPrimitive.int }, col.statisticsTypes)
        assertEquals(geo.columns["geometry"]!!.geometryTypes, col.geometryTypes)
        assertEquals(listOf(union.xmin, union.ymin, union.zmin, union.mmin, union.xmax, union.ymax, union.zmax, union.mmax), col.bbox)
        // Strings and doubles survive too; the string chunk has byte-order min/max, the geometry chunk none.
        val names = pf.readColumn("name") as ColumnValues.Binary
        assertEquals((name as ColumnValues.Binary).string(3), names.string(3))
        assertEquals(ratio.asDouble(5), pf.readColumn("ratio").asDouble(5))
        assertNotNull(pf.metadata.rowGroups[0].columns[1].statistics!!.minValue)
        assertNull(pf.metadata.rowGroups[0].columns[3].statistics!!.minValue)
        saveSample("geoparquet-samples", "round-trip-lz4.parquet", out.toByteArray())
    }

    private fun slice(v: ColumnValues, from: Int, to: Int): ColumnValues {
        val valid = v.valid?.copyOfRange(from, to)
        return when (v) {
            is ColumnValues.Int32 -> ColumnValues.Int32(v.values.copyOfRange(from, to), valid)
            is ColumnValues.Int64 -> ColumnValues.Int64(v.values.copyOfRange(from, to), valid)
            is ColumnValues.Float32 -> ColumnValues.Float32(v.values.copyOfRange(from, to), valid)
            is ColumnValues.Float64 -> ColumnValues.Float64(v.values.copyOfRange(from, to), valid)
            is ColumnValues.Binary -> {
                val base = v.offsets[from]
                ColumnValues.Binary(IntArray(to - from + 1) { v.offsets[from + it] - base }, v.data.copyOfRange(base, v.offsets[to]), valid)
            }
        }
    }

    // ---------------------------------------------------------------- units

    @Test
    fun `WKB decoder handles both byte orders and EWKB flags and refuses garbage`() {
        val le = Wkb.decode(pointWkb(1.5, -2.0, little = true))
        val be = Wkb.decode(pointWkb(1.5, -2.0, little = false))
        assertEquals("POINT (1.5 -2)", le.toWkt())
        assertEquals(le.toWkt(), be.toWkt())
        // EWKB point with SRID flag: 0x20000001, SRID 4326.
        val ewkb = TestBytes().put(1).putInt(0x20000001).putInt(4326).putDouble(3.0).putDouble(4.0).toByteArray()
        assertEquals("POINT (3 4)", Wkb.decode(ewkb).toWkt())
        for (bad in listOf(byteArrayOf(), byteArrayOf(2, 1, 0, 0, 0), pointWkb(1.0, 1.0, little = true).copyOf(12), byteArrayOf(1, 99, 0, 0, 0))) {
            try { Wkb.decode(bad); fail("accepted ${bad.toList()}") } catch (_: ParquetFormatException) { }
        }
    }

    private fun pointWkb(x: Double, y: Double, little: Boolean): ByteArray = TestBytes(littleEndian = little).put(if (little) 1 else 0).putInt(1).putDouble(x).putDouble(y).toByteArray()

    @Test
    fun `CRS strings are interpreted the way the spec tells a non-CRS-aware reader to`() {
        assertEquals(Crs.LonLatWgs84, Crs.parse(null))
        assertEquals(Crs.LonLatWgs84, Crs.parse(""))
        assertEquals(Crs.LonLatWgs84, Crs.parse("OGC:CRS84"))
        assertEquals(Crs.LonLatWgs84, Crs.parse("EPSG:4326"))
        assertEquals(Crs.LonLatWgs84, Crs.parse("srid:4326"))
        assertEquals(Crs.Unknown("srid:0"), Crs.parse("srid:0"))
        assertEquals(Crs.Srid(3857), Crs.parse("srid:3857"))
        assertEquals(Crs.Authority("EPSG", "3857"), Crs.parse("EPSG:3857"))
        assertEquals(Crs.LonLatWgs84, Crs.parse(Crs.CRS84_PROJJSON.toString()))
        assertEquals(Crs.LonLatWgs84, Crs.parse("projjson:my_crs", mapOf("my_crs" to Crs.CRS84_PROJJSON.toString())))
        assertTrue(Crs.parse("""{"type":"ProjectedCRS","id":{"authority":"EPSG","code":3857}}""") is Crs.ProjJson)
        assertTrue(Crs.parse("{not json") is Crs.Unknown)
        assertEquals(Crs.LonLatWgs84, Crs.fromGeoJson(null))
        assertEquals(Crs.Unknown(null), Crs.fromGeoJson(JsonNull))
        // GeoParquet-level parse of a geo key with unknown fields and a null crs.
        val geo = GeoParquetMetadata.parse("""{"version":"2.0.0","primary_column":"g","columns":{"g":{"encoding":"WKB","geometry_types":[],"crs":null,"future_field":1}},"vendor":"x"}""")!!
        assertEquals(2, geo.majorVersion)
        assertEquals(JsonNull, geo.columns["g"]!!.crs)
        assertNull(GeoParquetMetadata.parse("nonsense"))
        assertNull(geo.columns["g"]!!.bbox)
    }

    @Test
    fun `Snappy decoder handles literals and near and far copies and overlapping runs`() {
        // "abcabcabcabcXYZ" = literal "abc" + copy(offset 3, len 9) + literal "XYZ"; preamble = 15.
        val block = byteArrayOf(
            15, // uncompressed length varint
            (2 shl 2).toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), // literal len 3
            (((9 - 4) shl 2) or 1).toByte(), 3, // copy-1: len 9, offset 3 (overlapping run)
            (2 shl 2).toByte(), 'X'.code.toByte(), 'Y'.code.toByte(), 'Z'.code.toByte(),
        )
        assertEquals("abcabcabcabcXYZ", Snappy.decompress(block, 15).decodeToString())
        // copy-2 with a 2-byte offset and a 60+ byte literal length form.
        val long = ByteArray(70) { ('a' + it % 26).code.toByte() }
        val out = TestBytes()
        out.put(byteArrayOf((140 and 0x7F or 0x80).toByte(), (140 ushr 7).toByte())) // 140 = 70 literal + 70 copied
        out.put((60 shl 2)); out.put(69) // literal, 1-byte length form: 69 + 1 = 70
        out.put(long)
        out.put((((64 - 1) shl 2) or 2)); out.put(70); out.put(0) // copy-2: len 64 (the field's maximum), offset 70
        out.put((((6 - 4) shl 2) or 1)); out.put(70) // copy-1: len 6, offset 70 → 70 + 64 + 6 = 140
        val result = Snappy.decompress(out.toByteArray(), 140)
        assertEquals(long.decodeToString() + long.decodeToString(), result.decodeToString())
        try { Snappy.decompress(byteArrayOf(5, (3 shl 2).toByte(), 1, 2), 5); fail() } catch (_: ParquetFormatException) { }
    }
}
