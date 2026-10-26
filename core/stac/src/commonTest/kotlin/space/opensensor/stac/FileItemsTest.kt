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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileItemsTest {
    private fun item(bytes: ByteArray, mobility: Mobility?, template: space.opensensor.model.BoardTemplate = coreS3Template, name: String = FILE_NAME) =
        FileItems.build(name, ByteArrayReader(bytes), footer(mobility), template, h3)

    private fun json(result: FileItemResult): JsonObject = Json.parseToJsonElement(assertIs<FileItemResult.Item>(result).json).jsonObject

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
        assertEquals(0.008, properties["anon:size"]!!.jsonPrimitive.double)
        assertEquals(FileItems.FIXED_WARNING, properties["anon:warning"]!!.jsonPrimitive.content)
        assertEquals(10.0, properties["opensensor:sample_interval_s"]!!.jsonPrimitive.double)

        val data = item["assets"]!!.jsonObject["data"]!!.jsonObject
        assertEquals(
            "${FileItems.DATA_BASE_URL}thing=$THING/year=2026/month=10/day=24/$FILE_NAME",
            data["href"]!!.jsonPrimitive.content,
        )
        assertEquals(6, data["table:row_count"]!!.jsonPrimitive.int)
        assertEquals(bytes.size.toLong(), data["file:size"]!!.jsonPrimitive.long)
        val sha = Sha256().apply { update(bytes, 0, bytes.size) }.digest().toHex()
        assertEquals("1220$sha", data["file:checksum"]!!.jsonPrimitive.content)
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
        assertEquals(FileItems.MOVING_WARNING, properties["anon:warning"]!!.jsonPrimitive.content)

        val geometry = item["geometry"]!!.jsonObject
        val union = h3.cellsToMultiPolygon(listOf(a, b))
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
    fun mixedResolutionsAreCoarsenedBeforeTheUnion() {
        val fine = h3.latLngToCell(5.6018, -0.1881, 10)
        val rows = rowsIn(OSU_CELL, 2) + rowsIn(fine, 2).map { it.copy(utcUs = it.utcUs!! + 20_000_000L) }
        val geometry = json(item(deviceFile(rows), Mobility.MOVING))["geometry"]!!.jsonObject
        assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
        assertEquals(7, geometry["coordinates"]!!.jsonArray.single().jsonArray.size, "the res 10 cell lies in the res 8 cell")
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
        assertEquals(FileItemResult.NoThingId, FileItems.build(FILE_NAME, ByteArrayReader(bytes), null, coreS3Template, h3))
        assertEquals(
            FileItemResult.NoThingId,
            FileItems.build(FILE_NAME, ByteArrayReader(bytes), footer(Mobility.FIXED, thing = "not-a-uuid"), coreS3Template, h3),
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
        assertEquals(2, pm["count"]!!.jsonPrimitive.int)
        assertEquals(50.0, pm["valid_percent"]!!.jsonPrimitive.double)

        val t = byName.getValue("ambient_temperature_c").jsonObject["statistics"]!!.jsonObject
        assertEquals(27.25, t["minimum"]!!.jsonPrimitive.double)
        assertEquals(28.5, t["maximum"]!!.jsonPrimitive.double)
        assertEquals(27.875, t["mean"]!!.jsonPrimitive.double)
        assertEquals(50.0, t["valid_percent"]!!.jsonPrimitive.double)
    }

    @Test
    fun aColumnWithNoValidRowHasOnlyItsCount() {
        val rows = rowsIn(OSU_CELL, 3).map { it.copy(pmsStatus = 1) }
        val columns = json(item(deviceFile(rows), Mobility.FIXED))["assets"]!!.jsonObject["data"]!!.jsonObject["table:columns"] as JsonArray
        val pm = columns.first { it.jsonObject["name"]!!.jsonPrimitive.content == "pm25_atmospheric_ug_m3" }.jsonObject["statistics"]!!.jsonObject
        assertEquals(setOf("count", "valid_percent"), pm.keys)
        assertEquals(0, pm["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun timesKeepTheirFractionAndCrossMidnightByTheFirstRow() {
        assertEquals("2026-10-24T13:00:00.25Z", FileItems.rfc3339(HOUR_S * 1_000_000_000L + 250_000_000L))
        assertEquals("1970-01-01T00:00:00Z", FileItems.rfc3339(0))
        assertEquals("2024-02-29T23:59:59Z", FileItems.rfc3339(1_709_251_199L * 1_000_000_000L))
        assertEquals("thing=$THING/year=2026/month=01/day=05/x.json", FileItems.sidecarPath(THING, 1_767_571_200L * 1_000_000_000L, "x"))
    }
}
