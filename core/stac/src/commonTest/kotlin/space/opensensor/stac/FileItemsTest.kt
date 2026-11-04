package space.opensensor.stac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import space.opensensor.data.history.OpenSensorFooter.Mobility
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.protocol.Sha256
import space.opensensor.data.protocol.toHex
import space.opensensor.publicfeed.H3Index
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileItemsTest {
    private fun item(bytes: ByteArray, mobility: Mobility?, template: space.opensensor.model.BoardTemplate = coreS3Template, name: String = FILE_NAME) =
        FileItems.build(name, ByteArrayReader(bytes), footer(mobility), template, PUBLISHED_RES, h3)

    private fun json(result: FileItemResult): JsonObject = Json.parseToJsonElement(assertIs<FileItemResult.Item>(result).json).jsonObject

    @Test
    fun aResolution8CellIsPublishedAsItIs() {
        assertEquals(8, H3Index.resolution(OSU_CELL))
        val properties = json(item(deviceFile(rowsIn(OSU_CELL, 6)), Mobility.FIXED))["properties"]!!.jsonObject
        assertEquals("H3-${H3Index.toHex(OSU_CELL)}", properties["grid:code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFileFinerThanThePublishedResolutionGetsNoItem() {
        val fine = h3.latLngToCell(5.6018, -0.1881, 10)
        assertEquals(FileItemResult.WrongResolution(10), item(deviceFile(rowsIn(fine, 6)), Mobility.FIXED))
    }

    @Test
    fun aFileCoarserThanThePublishedResolutionGetsNoItem() {
        val coarse = h3.latLngToCell(5.6018, -0.1881, 7)
        assertEquals(FileItemResult.WrongResolution(7), item(deviceFile(rowsIn(coarse, 6)), Mobility.FIXED))
    }

    @Test
    fun onePublishedCellAmongOthersIsNotEnough() {
        // A publisher never coarsens, so a res 10 row among res 8 rows keeps the whole file out.
        val fine = h3.latLngToCell(5.6018, -0.1881, 10)
        val rows = rowsIn(OSU_CELL, 2) + rowsIn(fine, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        assertEquals(FileItemResult.WrongResolution(10), item(deviceFile(rows), Mobility.MOVING))
        assertEquals(FileItemResult.WrongResolution(10), item(deviceFile(rows), Mobility.FIXED))
    }

    @Test
    fun aPublishedResOutsideH3IsRefused() {
        val bytes = deviceFile(rowsIn(OSU_CELL, 6))
        for (res in listOf(-1, H3Index.MAX_RES + 1)) {
            assertFailsWith<IllegalArgumentException> { FileItems.build(FILE_NAME, ByteArrayReader(bytes), footer(Mobility.FIXED), coreS3Template, res, h3) }
        }
    }

    @Test
    fun fixedThingGetsItsCellPolygonAndGridCode() {
        val bytes = deviceFile(rowsIn(OSU_CELL, 6))
        val result = assertIs<FileItemResult.Item>(item(bytes, Mobility.FIXED))
        assertEquals("data_1300_0f3a9c2e5b7d41e8a6c3f0b2d9e4a713_2881-3240-0", result.id)
        assertEquals("thing=$THING/year=2026/month=10/day=24/${result.id}.json", result.sidecarPath)

        val item = json(result)
        assertEquals("Feature", item["type"]!!.jsonPrimitive.content)
        assertEquals(THING, item["collection"]!!.jsonPrimitive.content)
        val geometry = item["geometry"]!!.jsonObject
        assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
        val ring = geometry["coordinates"]!!.jsonArray.single().jsonArray
        assertEquals(7, ring.size, "a hexagon closed on its first vertex")
        assertEquals(ring.first(), ring.last())
        assertEquals(4, item["bbox"]!!.jsonArray.size)

        val properties = item["properties"]!!.jsonObject
        assertEquals(JsonNull, properties["datetime"])
        assertEquals("2026-10-24T13:00:00Z", properties["start_datetime"]!!.jsonPrimitive.content)
        assertEquals("2026-10-24T13:00:50Z", properties["end_datetime"]!!.jsonPrimitive.content)
        assertEquals("fixed", properties["opensensor:mobility"]!!.jsonPrimitive.content)
        assertEquals("H3-${H3Index.toHex(OSU_CELL)}", properties["grid:code"]!!.jsonPrimitive.content)
        assertEquals("0.01", properties["anon:size"]!!.jsonPrimitive.content, "the profile's constant")
        assertEquals(FileItems.FIXED_WARNING, properties["anon:warning"]!!.jsonPrimitive.content)
        assertEquals("10", properties["opensensor:sample_interval_s"]!!.jsonPrimitive.content, "a whole number of seconds")
        assertEquals(FileItems.EXTENSIONS, item["stac_extensions"]!!.jsonArray.map { it.jsonPrimitive.content })
        val links = item["links"]!!.jsonArray.map { it.jsonObject["rel"]!!.jsonPrimitive.content }
        assertEquals(listOf("root", "parent", "collection"), links, "the profile's order")

        val data = item["assets"]!!.jsonObject["data"]!!.jsonObject
        assertEquals("./$FILE_NAME", data["href"]!!.jsonPrimitive.content, "relative, so the Item stays valid when its folder moves")
        assertEquals(setOf("href", "type", "roles", "file:size", "file:checksum", "table:row_count", "table:columns"), data.keys)
        assertEquals(6, data["table:row_count"]!!.jsonPrimitive.int)
        assertEquals(bytes.size.toLong(), data["file:size"]!!.jsonPrimitive.long)
        val sha = Sha256().apply { update(bytes, 0, bytes.size) }.digest().toHex()
        assertEquals("1220$sha", data["file:checksum"]!!.jsonPrimitive.content)
    }

    @Test
    fun theFixedGeometryIsTheProfileExampleRing() {
        // stac-opensensor v0.1.0 (9943282), the fixed example Item. Every writer builds the same ring,
        // within the 1e-9 degrees its own check allows, because H3 builds differ in the last bits.
        val cell = H3Index.fromHex("887576970bfffff")
        val geometry = json(item(deviceFile(rowsIn(cell)), Mobility.FIXED))["geometry"]!!.jsonObject
        val ring = geometry["coordinates"]!!.jsonArray.single().jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
        val example = listOf(
            listOf(-0.18879745033151518, 5.597861712437427),
            listOf(-0.18483174817989717, 5.5986605211969),
            listOf(-0.18411316394230728, 5.602568013027744),
            listOf(-0.1873606129471792, 5.605676661461849),
            listOf(-0.19132642369093855, 5.604877563219264),
            listOf(-0.19204467683924653, 5.600970106043236),
            listOf(-0.18879745033151518, 5.597861712437427),
        )
        assertEquals(example.size, ring.size)
        example.zip(ring).forEach { (want, got) ->
            assertEquals(want[0], got[0], 1e-9)
            assertEquals(want[1], got[1], 1e-9)
        }
    }

    @Test
    fun noCoordinateOfTheFileReachesTheItem() {
        // The geometry is the cell, never a fix, so the cell centre is not a vertex either.
        val item = json(item(deviceFile(rowsIn(OSU_CELL)), Mobility.FIXED))
        val centre = h3.cellToLatLng(OSU_CELL)
        val vertices = item["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.single().jsonArray
        assertFalse(vertices.any { it.jsonArray[0].jsonPrimitive.double == centre.lng && it.jsonArray[1].jsonPrimitive.double == centre.lat })
    }

    @Test
    fun movingThingGetsTheUnionOfVisitedCells() {
        val a = OSU_CELL
        val b = h3.latLngToCell(5.6100, -0.1881, 8)
        val rows = rowsIn(a, 3) + rowsIn(b, 3).mapIndexed { i, r -> r.copy(utcUs = (HOUR_S + 30L + 10L * i) * 1_000_000L) }
        val item = json(item(deviceFile(rows), Mobility.MOVING, waveshareTemplate))
        val properties = item["properties"]!!.jsonObject
        assertEquals("moving", properties["opensensor:mobility"]!!.jsonPrimitive.content)
        assertNull(properties["grid:code"])
        assertEquals("0.01", properties["anon:size"]!!.jsonPrimitive.content)
        assertEquals(FileItems.MOVING_WARNING, properties["anon:warning"]!!.jsonPrimitive.content)
        val extensions = item["stac_extensions"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(FileItems.EXTENSIONS - FileItems.GRID_EXTENSION, extensions, "a moving Thing has no grid:code, so no Grid")

        val geometry = item["geometry"]!!.jsonObject
        val union = h3.cellsToMultiPolygon(listOf(a, b).sortedBy(H3Index::toHex))
        if (union.size == 1) {
            assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
        } else {
            assertEquals("MultiPolygon", geometry["type"]!!.jsonPrimitive.content)
            assertEquals(union.size, geometry["coordinates"]!!.jsonArray.size)
        }
    }

    @Test
    fun adjacentCellsMergeIntoOnePolygon() {
        val a = OSU_CELL
        // The next cell east shares an edge with Osu.
        val boundary = h3.cellToBoundary(a)
        val centre = h3.cellToLatLng(a)
        val east = boundary.maxBy { it.lng }
        val b = h3.latLngToCell(centre.lat, east.lng + (east.lng - centre.lng) * 0.5, 8)
        assertTrue(a != b)
        val rows = rowsIn(a, 2) + rowsIn(b, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        val geometry = json(item(deviceFile(rows), Mobility.MOVING))["geometry"]!!.jsonObject
        assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
        val ring = geometry["coordinates"]!!.jsonArray.single().jsonArray
        assertEquals(ring.first(), ring.last())
        assertTrue(ring.size > 7, "two hexagons make one outline with more than six corners")
    }

    @Test
    fun farApartCellsMakeAMultiPolygon() {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        val rows = rowsIn(OSU_CELL, 2) + rowsIn(far, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        val geometry = json(item(deviceFile(rows), Mobility.MOVING))["geometry"]!!.jsonObject
        assertEquals("MultiPolygon", geometry["type"]!!.jsonPrimitive.content)
        assertEquals(2, geometry["coordinates"]!!.jsonArray.size)
    }

    @Test
    fun theUnionDoesNotDependOnTheOrderTheCellsWereVisited() {
        val far = h3.latLngToCell(5.70, -0.30, 8)
        val there = rowsIn(OSU_CELL, 2) + rowsIn(far, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        val back = rowsIn(far, 2) + rowsIn(OSU_CELL, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        assertEquals(
            json(item(deviceFile(there), Mobility.MOVING))["geometry"],
            json(item(deviceFile(back), Mobility.MOVING))["geometry"],
        )
    }

    @Test
    fun aFileWithoutUtcGetsNoItem() {
        val rows = rowsIn(OSU_CELL).mapIndexed { i, r -> if (i == 2) r.copy(utcUs = null) else r }
        assertEquals(FileItemResult.NoUtcAnchor, item(deviceFile(rows), Mobility.FIXED))
        val unanchored = rowsIn(OSU_CELL).map { it.copy(utcUs = null) }
        assertEquals(FileItemResult.NoUtcAnchor, item(deviceFile(unanchored), Mobility.FIXED))
    }

    @Test
    fun aFileWithoutLocationGetsNoItem() {
        assertTrue(FileItems.ITEM_NEEDS_LOCATION)
        assertEquals(FileItemResult.NoLocation, item(deviceFile(rowsIn(null)), Mobility.FIXED))
        assertEquals(FileItemResult.NoLocation, item(deviceFile(rowsIn(null), withCells = false), Mobility.MOVING))
    }

    @Test
    fun oneLocatedRowIsEnough() {
        val rows = rowsIn(null).mapIndexed { i, r -> if (i == 4) r.copy(cell = OSU_CELL) else r }
        val item = json(item(deviceFile(rows), Mobility.FIXED))
        assertEquals("H3-${H3Index.toHex(OSU_CELL)}", item["properties"]!!.jsonObject["grid:code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFixedThingInTwoCellsGetsNoItem() {
        val other = h3.latLngToCell(5.70, -0.30, 8)
        val rows = rowsIn(OSU_CELL, 2) + rowsIn(other, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        assertEquals(FileItemResult.FixedThingMoved(2), item(deviceFile(rows), Mobility.FIXED))
    }

    @Test
    fun onlyDatedFilesWithAThingAndMobilityGetAnItem() {
        val bytes = deviceFile(rowsIn(OSU_CELL))
        assertEquals(FileItemResult.NotDatedFile, item(bytes, Mobility.FIXED, name = "data_unsynced_0001_ab_1-2-0.parquet"))
        assertEquals(FileItemResult.NotDatedFile, item(bytes, Mobility.FIXED, name = "day_2026-10-24.parquet"))
        assertEquals(FileItemResult.NoMobility, item(bytes, null))
        assertEquals(FileItemResult.NoThingId, FileItems.build(FILE_NAME, ByteArrayReader(bytes), null, coreS3Template, PUBLISHED_RES, h3))
        assertEquals(
            FileItemResult.NoThingId,
            FileItems.build(FILE_NAME, ByteArrayReader(bytes), footer(Mobility.FIXED, thing = "not-a-uuid"), coreS3Template, PUBLISHED_RES, h3),
        )
        assertEquals(FileItemResult.NoRows, item(deviceFile(emptyList()), Mobility.FIXED))
    }

    @Test
    fun statisticsCountOnlyRowsThatPassTheGate() {
        val rows = listOf(
            Row(HOUR_S * 1_000_000L, OSU_CELL, pmsStatus = 4, pm25 = 9, temperature = 27.25f),
            Row((HOUR_S + 10) * 1_000_000L, OSU_CELL, pmsStatus = 4, pm25 = 22, temperature = 28.5f),
            // Warming up, the reading does not count.
            Row((HOUR_S + 20) * 1_000_000L, OSU_CELL, pmsStatus = 2, pm25 = 0, temperature = null),
            Row((HOUR_S + 30) * 1_000_000L, OSU_CELL, pmsStatus = 4, pm25 = null, temperature = Float.NaN),
        )
        val columns = json(item(deviceFile(rows), Mobility.FIXED))["assets"]!!.jsonObject["data"]!!.jsonObject["table:columns"]!!.jsonArray
        val byName = columns.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("pm25_atmospheric_ug_m3", "ambient_temperature_c"), byName.keys, "only measurements the file holds")

        val pm25 = byName.getValue("pm25_atmospheric_ug_m3").jsonObject
        assertEquals("int32", pm25["type"]!!.jsonPrimitive.content)
        val pm = pm25["statistics"]!!.jsonObject
        assertEquals(9L, pm["minimum"]!!.jsonPrimitive.long)
        assertEquals(22L, pm["maximum"]!!.jsonPrimitive.long)
        assertEquals(15.5, pm["mean"]!!.jsonPrimitive.double)
        assertEquals(50.0, pm["valid_percent"]!!.jsonPrimitive.double)
        assertEquals(setOf("minimum", "maximum", "mean", "valid_percent"), pm.keys, "the profile's statistics")

        val t = byName.getValue("ambient_temperature_c").jsonObject["statistics"]!!.jsonObject
        assertEquals(27.25, t["minimum"]!!.jsonPrimitive.double)
        assertEquals(28.5, t["maximum"]!!.jsonPrimitive.double)
        assertEquals(27.88, t["mean"]!!.jsonPrimitive.double, "27.875 rounds half away from zero")
        assertEquals(50.0, t["valid_percent"]!!.jsonPrimitive.double)
    }

    @Test
    fun aColumnWithNoValidRowHasOnlyItsValidPercent() {
        val rows = rowsIn(OSU_CELL, 3).map { it.copy(pmsStatus = 1) }
        val columns = json(item(deviceFile(rows), Mobility.FIXED))["assets"]!!.jsonObject["data"]!!.jsonObject["table:columns"] as JsonArray
        val pm = columns.first { it.jsonObject["name"]!!.jsonPrimitive.content == "pm25_atmospheric_ug_m3" }.jsonObject["statistics"]!!.jsonObject
        assertEquals(setOf("valid_percent"), pm.keys)
        assertEquals("0", pm["valid_percent"]!!.jsonPrimitive.content)
    }

    @Test
    fun roundingIsHalfAwayFromZero() {
        assertEquals(0.13, ColumnStatistics.round2(0.125))
        assertEquals(-0.13, ColumnStatistics.round2(-0.125))
        assertEquals(98.89, ColumnStatistics.round2(100.0 * 89 / 90))
        assertEquals(96.67, ColumnStatistics.round2(100.0 * 87 / 90))
    }

    @Test
    fun timesKeepTheirFractionAndCrossMidnightByTheFirstRow() {
        assertEquals("2026-10-24T13:00:00.25Z", FileItems.rfc3339(HOUR_S * 1_000_000_000L + 250_000_000L))
        assertEquals("1970-01-01T00:00:00Z", FileItems.rfc3339(0))
        assertEquals("2024-02-29T23:59:59Z", FileItems.rfc3339(1_709_251_199L * 1_000_000_000L))
        assertEquals("thing=$THING/year=2026/month=01/day=05/x.json", FileItems.sidecarPath(THING, 1_767_571_200L * 1_000_000_000L, "x"))
    }
}
