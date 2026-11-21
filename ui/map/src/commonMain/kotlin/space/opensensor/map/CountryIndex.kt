package space.opensensor.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.opensensor.data.history.parquet.BoundingBox
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.Geometry
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.map.resources.Res
import space.opensensor.model.H3Cell
import space.opensensor.publicfeed.H3Grid

/**
 * Offline ISO 3166-1 alpha-2 country lookup over the bundled GeoParquet country boundaries
 * ([FILE], written by `tools/places/make_countries.py`). Nothing leaves the phone.
 */
class CountryIndex internal constructor(private val shapes: List<CountryShape>) {
    internal class CountryShape(
        val code: String,
        val bbox: BoundingBox,
        val polygons: List<Geometry.Polygon>,
    )

    val size: Int get() = shapes.size

    /**
     * The ISO 3166-1 alpha-2 code whose polygon contains ([lat], [lng]), or null in the ocean or
     * outside bundled boundaries.
     */
    fun countryFor(lat: Double, lng: Double): String? {
        for (shape in shapes) {
            val b = shape.bbox
            if (lng < b.xmin || lng > b.xmax || lat < b.ymin || lat > b.ymax) continue
            if (shape.polygons.any { contains(it, lng, lat) }) return shape.code
        }
        return null
    }

    /**
     * The country of H3 cell [cellHex] when its centre and every boundary vertex that falls inside
     * a country agree on the same code. Returns null when the cell straddles a border or has no
     * match, so a border cell never guesses.
     */
    fun countryForCell(h3: H3Grid, cellHex: String): String? {
        if (!h3.available) return null
        val cell = H3Cell.parseHex(cellHex) ?: return null
        val centre = runCatching { h3.cellToLatLng(cell) }.getOrNull() ?: return null
        val code = countryFor(centre.lat, centre.lng) ?: return null
        val boundary = runCatching { h3.cellToBoundary(cell) }.getOrNull().orEmpty()
        for (vertex in boundary) {
            val other = countryFor(vertex.lat, vertex.lng)
            if (other != null && other != code) return null
        }
        return code
    }

    companion object {
        /** The bundled GeoParquet file, written by `tools/places/make_countries.py`. */
        const val FILE = "files/places/world_countries_simple.parquet"

        private val ISO_ALPHA2 = Regex("[A-Z]{2}")

        /** Parses a GeoParquet file with `iso_a2` and a WKB Polygon/MultiPolygon geometry column. */
        fun parse(bytes: ByteArray): CountryIndex {
            ParquetFile.wrap(bytes, source = FILE).use { file ->
                val codes = file.readColumn("iso_a2") as? ColumnValues.Binary ?: return CountryIndex(emptyList())
                val geoCol = file.geometryColumns.firstOrNull() ?: return CountryIndex(emptyList())
                val geometries = file.readGeometry(geoCol)
                val out = ArrayList<CountryShape>(codes.size)
                for (i in 0 until minOf(codes.size, geometries.size)) {
                    val code = codes.string(i)?.trim()?.uppercase() ?: continue
                    if (!ISO_ALPHA2.matches(code)) continue
                    val geom = geometries[i] ?: continue
                    val bbox = geom.bbox() ?: continue
                    val polygons = when (geom) {
                        is Geometry.Polygon -> listOf(geom)
                        is Geometry.MultiPolygon -> geom.polygons
                        else -> emptyList()
                    }
                    if (polygons.isNotEmpty()) out += CountryShape(code, bbox, polygons)
                }
                return CountryIndex(out)
            }
        }

        private fun contains(polygon: Geometry.Polygon, x: Double, y: Double): Boolean {
            if (polygon.rings.isEmpty()) return false
            val stride = polygon.dims.count
            if (!ringContains(polygon.rings[0], stride, x, y)) return false
            for (i in 1 until polygon.rings.size) {
                if (ringContains(polygon.rings[i], stride, x, y)) return false
            }
            return true
        }

        /** Even-odd ray casting against one closed ring stored as interleaved `x, y, ...` doubles. */
        private fun ringContains(ring: DoubleArray, stride: Int, x: Double, y: Double): Boolean {
            val n = ring.size / stride
            if (n < 3) return false
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[i * stride]
                val yi = ring[i * stride + 1]
                val xj = ring[j * stride]
                val yj = ring[j * stride + 1]
                val intersects = (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }
    }
}

/** The bundled [CountryIndex], parsed once per process and kept. */
object CountryLookup {
    private val lock = Mutex()
    private var cached: CountryIndex? = null

    /** The bundled countries, decoded off the main thread on first use. */
    suspend fun index(): CountryIndex = cached ?: lock.withLock {
        cached ?: withContext(Dispatchers.Default) { CountryIndex.parse(Res.readBytes(CountryIndex.FILE)) }.also { cached = it }
    }
}

/** The bundled country boundaries for a composable, null while loading or unavailable. */
@Composable
fun rememberCountryIndex(): CountryIndex? {
    val index by produceState<CountryIndex?>(null) { value = catchingNonCancel { CountryLookup.index() }.getOrNull() }
    return index
}
