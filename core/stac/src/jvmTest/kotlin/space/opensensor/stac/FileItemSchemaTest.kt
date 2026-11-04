package space.opensensor.stac

import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.history.OpenSensorFooter.Mobility
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.publicfeed.H3Index
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import kotlin.test.assertIs

/**
 * Items validated against every schema a STAC validator applies to them, with no network.
 *
 * - The stac-opensensor v0.1.0 profile and extension schemas, its fixed and moving example Items, the
 *   row fixtures behind them and the footers they name, copied byte for byte from the v0.1.0 tag
 *   (commit 83787768cc8451109d48a03d4ef5493ffcd403dd) into `src/commonTest/fixtures/stac-opensensor`.
 *   The examples validate too, so a failure points at the writer, not at the copy.
 * - The core STAC 1.1.0 Item schema with the GeoJSON Feature and Geometry schemas it references, and
 *   the Grid v1.1.0, Anonymized Location v1.0.0, Table v1.3.0 and File v2.1.0 extension schemas.
 *   They are copied from their `$id` URLs on 2026-11-04 into `src/jvmTest/resources/schemas/`, one
 *   folder per host, with only line endings normalized. Every `https://` reference resolves there.
 *   As in any STAC validator, an extension schema applies to the Items that list it in `stac_extensions`.
 *
 * Items are built at the published resolution, 8, as `PublishGate` builds them for a board. The
 * Items rebuilt from the row fixtures validate too. `ProfileExampleTest` in commonTest checks that they
 * equal the examples as JSON values, on the JVM and on iOS.
 */
class FileItemSchemaTest {
    private val mapper = ObjectMapper()

    // The default loader never fetches remote resources, so a reference that misses the bundled copies fails.
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7) { builder ->
        builder.schemaIdResolvers { it.mapPrefix("https://", "classpath:schemas/") }
    }
    private val profileSchemas: List<Schema> = listOf("profile.json", "schema.json").map { name ->
        stacFile(name).inputStream().use { registry.getSchema(it) }
    }
    private val coreSchema: Schema = registry.getSchema(SchemaLocation.of(CORE_ITEM))

    /** The bundled schema of each STAC extension the writer can list, by its URL. */
    private val extensionSchemas: Map<String, Schema> = FileItems.EXTENSIONS
        .filter { it.startsWith(STAC_EXTENSIONS) }
        .associateWith { registry.getSchema(SchemaLocation.of(it)) }

    /** Every schema the Item [json] answers to, the profile, core STAC and each extension it lists. */
    private fun schemaErrors(json: String): List<Error> {
        val node = mapper.readTree(json)
        val listed = node.required("stac_extensions").values().map { it.asString() }.filter { it.startsWith(STAC_EXTENSIONS) }
        val schemas = profileSchemas + coreSchema + listed.map { checkNotNull(extensionSchemas[it]) { "no bundled schema for $it" } }
        return schemas.flatMap { schema -> schema.validate(node) }
    }

    private fun errors(json: String): List<String> = schemaErrors(json).map { it.toString() }

    private fun coreErrors(json: String): List<String> = coreSchema.validate(mapper.readTree(json)).map { it.toString() }

    /** Where each error points and which keyword raised it, independent of the message locale. */
    private fun List<Error>.sites(): List<String> = map { listOfNotNull(it.instanceLocation.toString(), it.keyword, it.property).joinToString(" ") }

    private fun assertSite(expected: String, errors: List<Error>) {
        assertTrue("expected $expected in ${errors.sites()}", expected in errors.sites())
    }

    private fun example(name: String): String = stacFile(name).decodeToString()

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
        val code = item.required("properties").required("grid:code").asString()
        val cell = code.removePrefix("H3-").toLong(16)
        assertEquals(8, H3Index.resolution(cell))
        assertEquals(OSU_CELL, cell)
        assertEquals(0.01, item.required("properties").required("anon:size").asDouble(), 0.0)
        assertEquals("./$FILE_NAME", item.required("assets").required("data").required("href").asString())
    }

    @Test
    fun aMovingWaveshareItemValidates() {
        val json = itemJson(movingRows(), Mobility.MOVING, waveshareTemplate)
        assertTrue(json.contains("\"MultiPolygon\""))
        assertFalse(FileItems.GRID_EXTENSION in mapper.readTree(json).required("stac_extensions").values().map { it.asString() })
        assertEquals(emptyList<String>(), errors(json))
    }

    @Test
    fun theSchemasCatchAFixedItemWithoutItsCell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item.required("properties") as ObjectNode).remove("grid:code")
        assertSite("/properties required grid:code", schemaErrors(mapper.writeValueAsString(item)))
    }

    @Test
    fun theProfileCatchesACellAtAnotherResolution() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item.required("properties") as ObjectNode).put("grid:code", "H3-${H3Index.toHex(H3Index.parent(OSU_CELL, 7))}")
        assertSite("/properties/grid:code pattern", schemaErrors(mapper.writeValueAsString(item)))
    }

    @Test
    fun theProfileCatchesAMovingItemThatListsGrid() {
        val item = mapper.readTree(itemJson(movingRows(), Mobility.MOVING, waveshareTemplate)) as ObjectNode
        (item.required("stac_extensions") as ArrayNode).add(FileItems.GRID_EXTENSION)
        assertSite("/stac_extensions not", schemaErrors(mapper.writeValueAsString(item)))
    }

    @Test
    fun theCoreSchemaCatchesAnItemWithoutLinks() {
        val json = itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)
        assertEquals(emptyList<String>(), coreErrors(json))
        val item = mapper.readTree(json) as ObjectNode
        item.remove("links")
        assertSite(" required links", coreSchema.validate(mapper.readTree(mapper.writeValueAsString(item))))
    }

    @Test
    fun theItemsRebuiltFromTheRowFixturesValidate() {
        for (rows in listOf(FIXED_ROWS, MOVING_ROWS)) {
            assertEquals(rows, emptyList<String>(), errors(assertIs<FileItemResult.Item>(rebuildExample(rows)).json))
        }
    }

    private companion object {
        const val CORE_ITEM = "https://schemas.stacspec.org/v1.1.0/item-spec/json-schema/item.json"
        const val STAC_EXTENSIONS = "https://stac-extensions.github.io/"
    }
}
