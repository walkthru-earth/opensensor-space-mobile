package earth.walkthru.aqsync.data.history.parquet

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The GeoParquet `geo` key (`FileMetaData.key_value_metadata`), versions 1.x and 2.0
 * (https://geoparquet.org/releases/v2.0.0-rc.1/). Unknown fields are ignored as the spec requires;
 * unknown *values* are kept as strings and refused only where they matter (a non-WKB `encoding`).
 */
@Serializable
data class GeoParquetMetadata(
    val version: String,
    @SerialName("primary_column") val primaryColumn: String,
    val columns: Map<String, GeoColumnMetadata> = emptyMap(),
) {
    /** `"1"` or `"2"`; null for a malformed version string. */
    val majorVersion: Int? get() = version.substringBefore('.').toIntOrNull()

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val KEY = "geo"

        /** What this writer emits: the rc.1 `schema.json` pins `version` to this const. */
        const val VERSION_2 = "2.0.0"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = false }

        fun parse(text: String): GeoParquetMetadata? = try { json.decodeFromString(serializer(), text) } catch (_: Exception) { null }

        /**
         * Builds the `geo` value for a file with one WKB geometry column in OGC:CRS84 (the `crs` key is omitted,
         * which is the spec's way of saying CRS84 — the writer never has to produce PROJJSON).
         * [geometryTypes] uses the spec's names (`"Point"`, `"Point Z"`, …); [bbox] is `[xmin, ymin, xmax, ymax]`
         * (or the 6/8-element Z/ZM forms).
         */
        fun forWriter(column: String, geometryTypes: List<String>, bbox: List<Double>?): GeoParquetMetadata = GeoParquetMetadata(
            version = VERSION_2,
            primaryColumn = column,
            columns = mapOf(column to GeoColumnMetadata(encoding = "WKB", geometryTypes = geometryTypes, bbox = bbox)),
        )
    }
}

@Serializable
data class GeoColumnMetadata(
    /** `"WKB"` for anything this reader decodes; 1.1 native encodings (`"point"`, …) are not supported. */
    val encoding: String? = null,
    @SerialName("geometry_types") val geometryTypes: List<String>? = null,
    /** PROJJSON object, [JsonNull] (unknown CRS) or absent = Kotlin `null` (CRS84). Kept raw: this app is not CRS-aware. */
    @Serializable(with = NullKeepingElementSerializer::class) val crs: JsonElement? = null,
    val orientation: String? = null,
    val edges: String? = null,
    val bbox: List<Double>? = null,
    val epoch: Double? = null,
)

/**
 * Decodes an explicit JSON `null` as [JsonNull] instead of Kotlin `null`, so "crs absent" (CRS84) and
 * "crs: null" (unknown) stay distinguishable as the GeoParquet spec requires.
 */
internal object NullKeepingElementSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable
    override fun deserialize(decoder: Decoder): JsonElement = (decoder as JsonDecoder).decodeJsonElement()
    override fun serialize(encoder: Encoder, value: JsonElement?) = (encoder as JsonEncoder).encodeJsonElement(value ?: JsonNull)
}

/**
 * The coordinate reference system of a geometry column as far as a non-CRS-aware reader can tell.
 * Parquet allows the annotation's `crs` string in several forms (Geospatial.md "CRS customization"),
 * GeoParquet 2.0 adds `srid:0` = unknown; the `geo` key carries inline PROJJSON or `null`.
 */
sealed class Crs {
    /** OGC:CRS84 / EPSG:4326 as GeoParquet defines them: longitude, latitude on WGS 84. */
    data object LonLatWgs84 : Crs()

    /** An `<authority>:<code>` other than the WGS 84 ones (`EPSG:3857`, …). */
    data class Authority(val authority: String, val code: String) : Crs()

    /** `srid:<n>` with n ≠ 0. */
    data class Srid(val srid: Int) : Crs()

    /** Inline PROJJSON this reader does not recognise as WGS 84 (kept verbatim for a CRS-aware consumer). */
    data class ProjJson(val projjson: JsonObject) : Crs()

    /** `srid:0`, `geo.crs = null`, or a form nobody understands. [raw] is what the file said. */
    data class Unknown(val raw: String?) : Crs()

    /** True for plain longitude/latitude data — what the map needs. */
    val isLonLat: Boolean get() = this is LonLatWgs84

    companion object {
        /**
         * Interprets the `crs` property of a GEOMETRY/GEOGRAPHY logical type. [keyValueMetadata] resolves the
         * `projjson:<key>` form (parquet-format) to the PROJJSON stored under that file key.
         */
        fun parse(raw: String?, keyValueMetadata: Map<String, String> = emptyMap()): Crs {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return LonLatWgs84
            if (text.startsWith("{")) return fromProjJsonText(text) ?: Unknown(raw)
            val colon = text.indexOf(':')
            if (colon <= 0) return Unknown(raw)
            val scheme = text.substring(0, colon).trim()
            val ident = text.substring(colon + 1).trim()
            return when (scheme.lowercase()) {
                "srid" -> ident.toIntOrNull()?.let {
                    if (it == 0) {
                        Unknown(raw)
                    } else if (it == 4326) {
                        LonLatWgs84
                    } else {
                        Srid(it)
                    }
                } ?: Unknown(raw)
                "projjson" -> keyValueMetadata[ident]?.let { fromProjJsonText(it) } ?: Unknown(raw)
                else -> authority(scheme, ident)
            }
        }

        /** Interprets the `geo` column `crs` field: absent = CRS84, `null` = unknown, object = PROJJSON. */
        fun fromGeoJson(element: JsonElement?): Crs = when (element) {
            null -> LonLatWgs84
            JsonNull -> Unknown(null)
            is JsonObject -> fromProjJson(element)
            is JsonPrimitive -> parse(element.contentOrNull)
            else -> Unknown(element.toString())
        }

        private fun authority(auth: String, code: String): Crs = when {
            auth.equals("OGC", true) && code.equals("CRS84", true) -> LonLatWgs84
            auth.equals("EPSG", true) && code == "4326" -> LonLatWgs84
            else -> Authority(auth, code)
        }

        private fun fromProjJsonText(text: String): Crs? = try { fromProjJson(Json.parseToJsonElement(text).jsonObject) } catch (_: Exception) { null }

        /**
         * The spec's own shortcut for non-CRS-aware readers: `id.authority`/`id.code` decide; anything else is
         * kept verbatim.
         */
        private fun fromProjJson(obj: JsonObject): Crs {
            val id = obj["id"] as? JsonObject
            val auth = id?.get("authority")?.jsonPrimitive?.contentOrNull
            val code = id?.get("code")?.let { p -> (p as? JsonPrimitive)?.let { it.intOrNull?.toString() ?: it.contentOrNull } }
            return if (auth != null && code != null) {
                when (val a = authority(auth, code)) {
                    is LonLatWgs84 -> a
                    else -> ProjJson(obj)
                }
            } else {
                ProjJson(obj)
            }
        }

        /** The PROJJSON for OGC:CRS84 from the spec, for writers that need to restate it. */
        val CRS84_PROJJSON: JsonObject = buildJsonObject {
            put("\$schema", "https://proj.org/schemas/v0.5/projjson.schema.json")
            put("type", "GeographicCRS")
            put("name", "WGS 84 longitude-latitude")
            putJsonObject("datum") {
                put("type", "GeodeticReferenceFrame")
                put("name", "World Geodetic System 1984")
                putJsonObject("ellipsoid") { put("name", "WGS 84"); put("semi_major_axis", 6378137); put("inverse_flattening", 298.257223563) }
            }
            putJsonObject("coordinate_system") {
                put("subtype", "ellipsoidal")
                putJsonArray("axis") {
                    add(buildJsonObject { put("name", "Geodetic longitude"); put("abbreviation", "Lon"); put("direction", "east"); put("unit", "degree") })
                    add(buildJsonObject { put("name", "Geodetic latitude"); put("abbreviation", "Lat"); put("direction", "north"); put("unit", "degree") })
                }
            }
            putJsonObject("id") { put("authority", "OGC"); put("code", "CRS84") }
        }
    }
}

/** A geometry column as the reader sees it, merged from the logical type and the `geo` key (see `ParquetFileMetadata.geometryColumns`). */
data class GeometryColumn(
    val leaf: LeafColumn,
    val crs: Crs,
    /** `planar`, `spherical`, `vincenty`, `thomas`, `andoyer`, `karney`. */
    val edges: String,
    val primary: Boolean,
    /** From `geo`, spec names (`"Point Z"`); null when the file did not say. Prefer [statisticsTypes]. */
    val geometryTypes: List<String>?,
    /** From `geo`, file-level `[xmin, ymin, (zmin, (mmin,)) xmax, ymax, (zmax, (mmax))]`. */
    val bbox: List<Double>?,
    val orientation: String?,
    /** One entry per row group that carries `geospatial_statistics`. */
    val statistics: List<GeospatialStatistics>,
) {
    val name: String get() = leaf.name

    /** The union of the row-group bounding boxes, or null when no row group has statistics. */
    val statisticsBbox: BoundingBox? get() = statistics.mapNotNull { it.bbox }.reduceOrNull { a, b -> a.union(b) }

    /** The union of the row-group type codes (ISO WKB integers), empty when unknown. */
    val statisticsTypes: List<Int> get() = statistics.flatMap { it.geospatialTypes }.distinct().sorted()

    /** Bbox from the `geo` key as a [BoundingBox] (X/Y only; Z/M when present). */
    val geoBbox: BoundingBox? get() = bbox?.let { b ->
        when (b.size) {
            4 -> BoundingBox(b[0], b[2], b[1], b[3])
            6 -> BoundingBox(b[0], b[3], b[1], b[4], b[2], b[5])
            8 -> BoundingBox(b[0], b[4], b[1], b[5], b[2], b[6], b[3], b[7])
            else -> null
        }
    }

    private fun <T> List<T>.reduceOrNull(op: (T, T) -> T): T? = if (isEmpty()) null else reduce(op)
}
