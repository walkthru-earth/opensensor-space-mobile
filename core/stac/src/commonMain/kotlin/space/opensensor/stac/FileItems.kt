package space.opensensor.stac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.protocol.Sha256
import space.opensensor.data.protocol.toHex
import space.opensensor.model.BoardTemplate
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import space.opensensor.publicfeed.platformH3Grid
import kotlin.math.min

/** What [FileItems.build] returns, an Item or the reason a file gets none. */
sealed interface FileItemResult {
    /** The Item [json] for the device file, to store at [sidecarPath] beside it. */
    data class Item(val id: String, val sidecarPath: String, val json: String) : FileItemResult

    /** The file gets no Item. Each reason is a file the public catalog must not list. */
    sealed interface NoItem : FileItemResult

    /** Not a dated `data_HHMM_` file. Unsynced files and app files are never published. */
    data object NotDatedFile : NoItem

    /** No `opensensor` footer, or a Thing id that is not a UUID. A parsed footer always has a Thing id. */
    data object NoThingId : NoItem

    /** A footer built without a mobility. A parsed footer always has one. */
    data object NoMobility : NoItem

    /** The file holds no rows. */
    data object NoRows : NoItem

    /** A row has no UTC time, so the file is not anchored to the calendar. */
    data object NoUtcAnchor : NoItem

    /** No row carries an H3 cell. See [FileItems.ITEM_NEEDS_LOCATION]. */
    data object NoLocation : NoItem

    /**
     * A located row whose cell is at resolution [res], not the published one. A publisher never
     * coarsens, so such a file is not published rather than given a cell its rows do not name.
     */
    data class WrongResolution(val res: Int) : NoItem

    /** A fixed Thing whose rows name more than one cell, which the profile cannot describe. */
    data class FixedThingMoved(val cells: Int) : NoItem

    /** This platform has no H3 library, so no cell polygon can be drawn. */
    data object NoH3 : NoItem
}

/**
 * The STAC File Item of a synced device file, as the stac-opensensor v0.1.0 profile (tag v0.1.0,
 * commit 9943282) asks.
 * The board writes this Item as a sidecar beside its file, and the phone is its transport and
 * validator. An Item built here must equal the board's Item as JSON values. Once upload exists, the
 * phone uploads the board's sidecar `.json` together with its `.parquet`.
 * Pure. It reads the verified file bytes and never uploads, writes or logs.
 *
 * The geometry is H3 cells only, never a device position. A fixed Thing gets its one cell polygon
 * and `grid:code`. A moving Thing gets the union of the cells its rows visited, a Polygon or a
 * MultiPolygon, and does not list Grid. Every cell is at the published resolution, and a file with
 * any other cell gets no Item, because a publisher never coarsens.
 */
object FileItems {
    /**
     * Bridge open question 41. True means a file whose rows carry no H3 cell gets no Item, so the
     * public catalog only lists located files. This is the one place that rule lives.
     */
    const val ITEM_NEEDS_LOCATION = true

    const val STAC_VERSION = "1.1.0"
    const val GRID_EXTENSION = "https://stac-extensions.github.io/grid/v1.1.0/schema.json"

    /** Every extension a File Item can list, in the profile's order. A moving Item leaves out [GRID_EXTENSION]. */
    val EXTENSIONS = listOf(
        "https://stac-extensions.github.io/table/v1.3.0/schema.json",
        "https://stac-extensions.github.io/file/v2.1.0/schema.json",
        GRID_EXTENSION,
        "https://stac-extensions.github.io/anonymized-location/v1.0.0/schema.json",
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/schema.json",
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/profile.json",
    )

    /**
     * `anon:size` of every Item, fixed or moving. Twice the mean H3 edge at resolution 8 in degrees,
     * 2 × 0.5314 km ÷ 111.32 km = 0.00955, rounded up as the profile says.
     */
    const val ANON_SIZE = 0.01

    const val H3_CELL_COLUMN = "h3_cell_id"
    const val PARQUET_MEDIA_TYPE = "application/vnd.apache.parquet"
    const val FIXED_WARNING = "The geometry is the H3 cell chosen by the owner, not the device position."
    const val MOVING_WARNING = "The geometry is the H3 cells the device visited, not its track."

    private val DATED_FILE = Regex("^data_\\d{4}_[^/]+\\.parquet$")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private const val NS_PER_S = 1_000_000_000L
    private const val HASH_CHUNK = 64 * 1024

    /**
     * The Item of the device file [fileName], whose verified bytes [reader] holds, with its parsed
     * [footer] and the [template] of the board that wrote it. Its data href is `./<fileName>`, relative
     * to the sidecar beside the file, so the Item stays valid in any bucket or mirror.
     * Every located row must name a cell at exactly [publishedRes], the precision of the publish
     * policy (`PublishGate` in `:core:thing`). Any other cell gives [FileItemResult.WrongResolution],
     * so the Item only names cells the file itself holds and equals the one the board wrote.
     * [publishedRes] has no default, so every caller states the precision it publishes.
     */
    fun build(
        fileName: String,
        reader: ByteReader,
        footer: OpenSensorFooter?,
        template: BoardTemplate,
        publishedRes: Int,
        h3: H3Grid = platformH3Grid(),
    ): FileItemResult {
        require(publishedRes in 0..H3Index.MAX_RES) { "publishedRes $publishedRes is not an H3 resolution" }
        if (!DATED_FILE.matches(fileName)) return FileItemResult.NotDatedFile
        if (footer == null) return FileItemResult.NoThingId
        val thing = footer.thingId?.takeIf { UUID.matches(it) } ?: return FileItemResult.NoThingId
        val mobility = footer.mobility ?: return FileItemResult.NoMobility
        val file = ParquetFile.open(reader, fileName)
        val rows = file.numRows.toInt()
        if (rows == 0) return FileItemResult.NoRows
        val times = UtcColumns.readEventNs(file) ?: return FileItemResult.NoUtcAnchor
        if (times.nullCount > 0) return FileItemResult.NoUtcAnchor
        val startNs = times.values.min()
        val endNs = times.values.max()

        val cells = visitedCells(file)
        cells.firstOrNull { H3Index.resolution(it) != publishedRes }?.let { return FileItemResult.WrongResolution(H3Index.resolution(it)) }
        val fixed = mobility == OpenSensorFooter.Mobility.FIXED
        val geometry: Geometry? = when {
            cells.isEmpty() -> if (ITEM_NEEDS_LOCATION) return FileItemResult.NoLocation else null
            !h3.available -> return FileItemResult.NoH3
            fixed && cells.size > 1 -> return FileItemResult.FixedThingMoved(cells.size)
            else -> Geometry.of(cells, h3)
        }

        val id = fileName.removeSuffix(".parquet")
        val json = buildJsonObject {
            put("type", "Feature")
            put("stac_version", STAC_VERSION)
            putJsonArray("stac_extensions") { EXTENSIONS.filter { fixed || it != GRID_EXTENSION }.forEach { add(it) } }
            put("id", id)
            put("collection", thing)
            put("geometry", geometry?.geoJson() ?: JsonNull)
            if (geometry != null) put("bbox", geometry.bbox())
            putJsonObject("properties") {
                put("datetime", JsonNull)
                put("start_datetime", rfc3339(startNs))
                put("end_datetime", rfc3339(endNs))
                put("opensensor:mobility", mobility.wire)
                footer.sampleIntervalMs?.let { put("opensensor:sample_interval_s", jsonNumber(it / 1000.0)) }
                if (geometry != null) {
                    if (fixed) put("grid:code", "H3-${H3Index.toHex(cells.single())}")
                    put("anon:size", ANON_SIZE)
                    put("anon:warning", if (fixed) FIXED_WARNING else MOVING_WARNING)
                }
            }
            putJsonArray("links") {
                link("root", "../../../../catalog.json")
                link("parent", "../../../collection.json")
                link("collection", "../../../collection.json")
            }
            putJsonObject("assets") {
                putJsonObject("data") {
                    put("href", "./$fileName")
                    put("type", PARQUET_MEDIA_TYPE)
                    putJsonArray("roles") { add("data") }
                    put("file:size", reader.size)
                    put("file:checksum", "1220" + sha256Hex(reader))
                    put("table:row_count", rows)
                    ColumnStatistics.of(file, template).takeIf { it.isNotEmpty() }?.let { put("table:columns", it) }
                }
            }
        }
        return FileItemResult.Item(id, sidecarPath(thing, startNs, id), json.toString())
    }

    /** Where the Item of file [id] sits, beside the file: `thing=<uuid>/year=YYYY/month=MM/day=DD/<id>.json`. */
    fun sidecarPath(thing: String, startNs: Long, id: String): String = "${partition(thing, startNs)}/$id.json"

    /** The Hive partition of a file whose first row is at [startNs], by its UTC date. */
    fun partition(thing: String, startNs: Long): String {
        val (y, m, d) = civil(startNs.floorDiv(NS_PER_S).floorDiv(86_400L))
        return "thing=$thing/year=$y/month=${pad2(m)}/day=${pad2(d)}"
    }

    /** The distinct H3 cells of the located rows, sorted by their 15 character hex as the profile asks. */
    private fun visitedCells(file: ParquetFile): List<Long> {
        val leaf = file.metadata.leaf(H3_CELL_COLUMN) ?: return emptyList()
        if (leaf.type != ParquetType.INT64) return emptyList()
        val column = file.readColumn(leaf) as? ColumnValues.Int64 ?: return emptyList()
        val seen = LinkedHashSet<Long>()
        for (i in 0 until column.size) {
            if (column.isNull(i)) continue
            val cell = column.values[i]
            if (cell != 0L) seen += cell
        }
        return seen.sortedBy(H3Index::toHex)
    }

    private fun sha256Hex(reader: ByteReader): String {
        val hash = Sha256()
        var offset = 0L
        while (offset < reader.size) {
            val n = min(HASH_CHUNK.toLong(), reader.size - offset).toInt()
            hash.update(reader.read(offset, n), 0, n)
            offset += n
        }
        return hash.digest().toHex()
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.link(rel: String, href: String) = addJsonObject {
        put("rel", rel)
        put("href", href)
        put("type", "application/json")
    }

    /** RFC 3339 UTC, with a fraction only when the instant has one. */
    internal fun rfc3339(ns: Long): String {
        val seconds = ns.floorDiv(NS_PER_S)
        val nanos = ns.mod(NS_PER_S)
        val days = seconds.floorDiv(86_400L)
        val secOfDay = seconds.mod(86_400L)
        val (y, m, d) = civil(days)
        val base = "${y.toString().padStart(4, '0')}-${pad2(m)}-${pad2(d)}T" +
            "${pad2((secOfDay / 3600).toInt())}:${pad2((secOfDay / 60 % 60).toInt())}:${pad2((secOfDay % 60).toInt())}"
        val fraction = if (nanos == 0L) "" else "." + nanos.toString().padStart(9, '0').trimEnd('0')
        return "${base}${fraction}Z"
    }

    private fun pad2(v: Int): String = v.toString().padStart(2, '0')

    /** Year, month and day of [days] since 1970-01-01, the proleptic Gregorian calendar. */
    private fun civil(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }
}

/**
 * An Item geometry built from H3 cells, polygons of closed rings in degrees. Every writer builds it
 * the same way, so a regenerated Item equals the board's. The sorted cells go through H3
 * `cellsToMultiPolygon`, each ring is closed on its first vertex, and one polygon is a Polygon while
 * more are a MultiPolygon. A fixed Thing follows the same steps with its one cell.
 */
internal class Geometry private constructor(
    /** Polygons, each its outer ring then its holes, every ring closed. */
    private val polygons: List<List<List<LatLng>>>,
) {
    fun geoJson(): JsonObject = buildJsonObject {
        if (polygons.size == 1) {
            put("type", "Polygon")
            put("coordinates", rings(polygons.single()))
        } else {
            put("type", "MultiPolygon")
            put("coordinates", JsonArray(polygons.map(::rings)))
        }
    }

    fun bbox(): JsonArray {
        val points = polygons.flatten().flatten()
        return buildJsonArray {
            add(points.minOf { it.lng })
            add(points.minOf { it.lat })
            add(points.maxOf { it.lng })
            add(points.maxOf { it.lat })
        }
    }

    private fun rings(polygon: List<List<LatLng>>): JsonArray = buildJsonArray {
        polygon.forEach { ring ->
            addJsonArray { ring.forEach { p -> addJsonArray { add(p.lng); add(p.lat) } } }
        }
    }

    companion object {
        /** The geometry of [cells], already sorted and all at one resolution. */
        fun of(cells: List<Long>, h3: H3Grid): Geometry =
            Geometry(h3.cellsToMultiPolygon(cells).map { polygon -> polygon.map(::close) })

        private fun close(ring: List<LatLng>): List<LatLng> = if (ring.first() == ring.last()) ring else ring + ring.first()
    }
}
