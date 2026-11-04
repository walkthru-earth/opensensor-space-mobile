package space.opensensor.stac

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

/**
 * Items validated against every schema a STAC validator applies to them, with no network.
 *
 * - The stac-opensensor v0.1.0 profile and extension schemas, copied from stac-opensensor 815fc45
 *   into `src/jvmTest/resources/stac-opensensor`. The example Item of that commit validates too, so
 *   a failure points at the writer, not at the copy.
 * - The core STAC 1.1.0 Item schema with the GeoJSON Feature and Geometry schemas it references, and
 *   the Grid v1.1.0, Anonymized Location v1.0.0, Table v1.3.0 and File v2.1.0 extension schemas.
 *   They are copied from their `$id` URLs on 2026-11-04 into `src/jvmTest/resources/schemas/`, one
 *   folder per host, with only line endings normalized. Every `https://` reference resolves there.
 *   As in any STAC validator, an extension schema applies to the Items that list it in `stac_extensions`.
 *
 * Items are built at the published resolution, 8, as `PublishGate` builds them for a board.
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

    /** Every schema the Item [json] answers to, the profile, core STAC and each extension it lists but [skip]. */
    private fun errors(json: String, skip: Set<String> = emptySet()): List<String> {
        val node = mapper.readTree(json)
        val listed = node["stac_extensions"].map { it.asText() }.filter { it.startsWith(STAC_EXTENSIONS) && it !in skip }
        val schemas = profileSchemas + coreSchema + listed.map { checkNotNull(extensionSchemas[it]) { "no bundled schema for $it" } }
        return schemas.flatMap { schema -> schema.validate(node).map { it.message } }
    }

    private fun coreErrors(json: String): List<String> = coreSchema.validate(mapper.readTree(json)).map { it.message }

    private fun itemJson(rows: List<Row>, mobility: Mobility, template: space.opensensor.model.BoardTemplate): String {
        val result = FileItems.build(FILE_NAME, ByteArrayReader(deviceFile(rows)), footer(mobility), template, PUBLISHED_RES, h3)
        return (result as FileItemResult.Item).json
    }

    @Test
    fun everyStacExtensionTheWriterListsHasABundledSchema() {
        assertEquals(4, extensionSchemas.size)
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
    fun aFixedItemNamesItsResolution8Cell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template))
        val code = item["properties"]["grid:code"].asText()
        val cell = code.removePrefix("H3-").toLong(16)
        assertEquals(8, H3Index.resolution(cell))
        assertEquals(OSU_CELL, cell)
        assertTrue("anon:size declares the res 8 cell", item["properties"]["anon:size"].asDouble() > 0.0)
        assertEquals("./$FILE_NAME", item["assets"]["data"]["href"].asText())
    }

    @Test
    fun aMovingWaveshareItemValidates() {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        val rows = rowsIn(OSU_CELL, 3) + rowsIn(far, 3).map { it.copy(utcUs = it.utcUs!! + 30_000_000L) }
        val json = itemJson(rows, Mobility.MOVING, waveshareTemplate)
        assertTrue(json.contains("\"MultiPolygon\""))
        assertEquals(emptyList<String>(), errors(json, skip = setOf(GRID)))
    }

    /**
     * Known conflict, left for the spec update (phase 2). The 815fc45 profile asks every Item to
     * list the Grid extension, and the Grid schema requires a `grid:code`, which a moving Item has
     * not. The rewritten profile drops Grid from moving Items. When this fails, drop the `skip` above.
     */
    @Test
    fun aMovingItemListsGridWithoutACodeUntilTheSpecUpdate() {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        val rows = rowsIn(OSU_CELL, 3) + rowsIn(far, 3).map { it.copy(utcUs = it.utcUs!! + 30_000_000L) }
        val json = itemJson(rows, Mobility.MOVING, waveshareTemplate)
        assertTrue(GRID in mapper.readTree(json)["stac_extensions"].map { it.asText() })
        assertFalse(errors(json).isEmpty())
    }

    @Test
    fun theSchemasCatchAFixedItemWithoutItsCell() {
        val item = mapper.readTree(itemJson(rowsIn(OSU_CELL), Mobility.FIXED, coreS3Template)) as ObjectNode
        (item["properties"] as ObjectNode).remove("grid:code")
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

    private companion object {
        const val CORE_ITEM = "https://schemas.stacspec.org/v1.1.0/item-spec/json-schema/item.json"
        const val STAC_EXTENSIONS = "https://stac-extensions.github.io/"
        const val GRID = "https://stac-extensions.github.io/grid/v1.1.0/schema.json"
    }
}
