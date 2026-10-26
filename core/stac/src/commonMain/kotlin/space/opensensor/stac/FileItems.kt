package space.opensensor.stac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

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

    /** A fixed Thing whose rows name more than one cell, which the profile cannot describe. */
    data class FixedThingMoved(val cells: Int) : NoItem

    /** This platform has no H3 library, so no cell polygon can be drawn. */
    data object NoH3 : NoItem
}

/**
 * One STAC File Item per synced device file, as the stac-opensensor v0.1.0 profile (815fc45) asks.
 * Pure. It reads the verified file bytes and never uploads, writes or logs.
 *
 * The geometry is H3 cells only, never a device position. A fixed Thing gets its one cell polygon
 * and `grid:code`. A moving Thing gets the union of the cells its rows visited, a Polygon or a
 * MultiPolygon. Cells of mixed resolution are coarsened to the coarsest one first.
 */
object FileItems {
    /**
     * Bridge open question 41. True means a file whose rows carry no H3 cell gets no Item, so the
     * public catalog only lists located files. This is the one place that rule lives.
     */
    const val ITEM_NEEDS_LOCATION = true

    /** Where the public archive serves `thing=` partitions. */
    const val DATA_BASE_URL = "https://data.source.coop/walkthru-earth/opensensor-space/"

    const val STAC_VERSION = "1.1.0"
    val EXTENSIONS = listOf(
        "https://stac-extensions.github.io/grid/v1.1.0/schema.json",
        "https://stac-extensions.github.io/anonymized-location/v1.0.0/schema.json",
        "https://stac-extensions.github.io/table/v1.3.0/schema.json",
        "https://stac-extensions.github.io/file/v2.1.0/schema.json",
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/schema.json",
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/profile.json",
    )

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
     * [footer] and the [template] of the board that wrote it. [dataBaseUrl] is the public archive root.
     */
    fun build(
        fileName: String,
        reader: ByteReader,
        footer: OpenSensorFooter?,
        template: BoardTemplate,
        h3: H3Grid = platformH3Grid(),
        dataBaseUrl: String = DATA_BASE_URL,
    ): FileItemResult {
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
        val geometry: Geometry? = when {
            cells.isEmpty() -> if (ITEM_NEEDS_LOCATION) return FileItemResult.NoLocation else null
            !h3.available -> return FileItemResult.NoH3
            mobility == OpenSensorFooter.Mobility.FIXED -> {
                if (cells.size > 1) return FileItemResult.FixedThingMoved(cells.size)
                Geometry.fixed(cells.single(), h3)
            }
            else -> Geometry.moving(cells, h3)
        }

        val id = fileName.removeSuffix(".parquet")
        val partition = partition(thing, startNs)
        val json = buildJsonObject {
            put("type", "Feature")
            put("stac_version", STAC_VERSION)
            putJsonArray("stac_extensions") { EXTENSIONS.forEach { add(it) } }
            put("id", id)
            put("collection", thing)
            put("geometry", geometry?.geoJson() ?: JsonNull)
            if (geometry != null) put("bbox", geometry.bbox())
            putJsonObject("properties") {
                put("datetime", JsonNull)
                put("start_datetime", rfc3339(startNs))
                put("end_datetime", rfc3339(endNs))
                put("opensensor:mobility", mobility.wire)
                footer.sampleIntervalMs?.let { put("opensensor:sample_interval_s", it / 1000.0) }
                if (geometry != null) {
                    if (mobility == OpenSensorFooter.Mobility.FIXED) put("grid:code", "H3-${H3Index.toHex(cells.single())}")
                    put("anon:size", geometry.anonSize)
                    put("anon:warning", if (mobility == OpenSensorFooter.Mobility.FIXED) FIXED_WARNING else MOVING_WARNING)
                }
            }
            putJsonArray("links") {
                link("root", "../../../../catalog.json")
                link("collection", "../../../collection.json")
                link("parent", "../../../collection.json")
            }
            putJsonObject("assets") {
                putJsonObject("data") {
                    put("href", "$dataBaseUrl$partition/$fileName")
                    put("type", PARQUET_MEDIA_TYPE)
                    put("title", "One file of samples, at most 1 hour")
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

    /** The distinct H3 cells of the located rows, in first seen order. */
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
        return seen.toList()
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

/** An Item geometry built from H3 cells, polygons of closed rings in degrees. */
internal class Geometry private constructor(
    /** Polygons, each its outer ring then its holes, every ring closed. */
    private val polygons: List<List<List<LatLng>>>,
    /** `anon:size`, the widest extent in degrees of one cell, rounded up to 3 decimals. */
    val anonSize: Double,
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
        fun fixed(cell: Long, h3: H3Grid): Geometry {
            val ring = h3.cellToBoundary(cell)
            return Geometry(listOf(listOf(close(ring))), anonSize(listOf(ring)))
        }

        fun moving(cells: List<Long>, h3: H3Grid): Geometry {
            val res = cells.minOf { H3Index.resolution(it) }
            val coarse = cells.map { if (H3Index.resolution(it) == res) it else H3Index.parent(it, res) }.distinct()
            val polygons = h3.cellsToMultiPolygon(coarse).map { polygon -> polygon.map(::close) }
            return Geometry(polygons, anonSize(coarse.map(h3::cellToBoundary)))
        }

        private fun close(ring: List<LatLng>): List<LatLng> = if (ring.first() == ring.last()) ring else ring + ring.first()

        private fun anonSize(boundaries: List<List<LatLng>>): Double {
            val widest = boundaries.maxOf { b ->
                max(b.maxOf { it.lng } - b.minOf { it.lng }, b.maxOf { it.lat } - b.minOf { it.lat })
            }
            return ceil(round(widest * 1e9) / 1e6) / 1000.0
        }
    }
}
