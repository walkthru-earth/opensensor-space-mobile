package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.WhoGuidelines
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.opensensor.model.Scale
import space.opensensor.model.StationCollection

/**
 * The discover Collection, `discover/collection.json` (docs/devices-and-discovery.md). A STAC
 * Collection of H3 aggregates with one asset per `h3_res`. Its `item_assets.data.table:columns` name
 * `<id>__n`, `__mean`, `__min` and `__max` per property. The `__mean` column carries the property's
 * `title` and, when one applies, its `opensensor:scale`, a key of `opensensor:scales`.
 *
 * [asOf] is the UTC date of the end of `extent.temporal.interval`, the newest complete day. [generation]
 * is `updated`, which changes whenever the files are republished in place. [cells] maps each
 * resolution to the URL of its cells file.
 */
class DiscoverCollection private constructor(
    private val stac: StationCollection,
    val asOf: LocalDate?,
    val generation: String?,
    val cells: Map<Int, String>,
) {
    /** The column title of [propertyId]'s mean, or null when the Collection gives none. */
    fun title(propertyId: String): String? = stac.columnTitles[mean(propertyId)]

    /** The scale of [propertyId]'s mean, or null when the Collection gives none. */
    fun scale(propertyId: String): Scale? = stac.columnScales[mean(propertyId)]?.let { stac.scales[it] }

    /**
     * [properties] with the Collection's title as the label when it has one, and its scale. A property
     * the Collection does not describe keeps its dictionary label and has no scale.
     */
    fun describe(properties: List<FeedProperty>): List<FeedProperty> =
        properties.map { p -> p.copy(label = title(p.id) ?: p.label, scale = scale(p.id)) }

    @Serializable
    private class Dto(
        val type: String,
        val updated: String? = null,
        val extent: ExtentDto? = null,
        val assets: Map<String, AssetDto> = emptyMap(),
    )

    @Serializable
    private class ExtentDto(val temporal: TemporalDto? = null)

    @Serializable
    private class TemporalDto(val interval: List<List<String?>> = emptyList())

    @Serializable
    private class AssetDto(val href: String, @SerialName("type") val mediaType: String? = null)

    companion object {
        const val FILE: String = "collection.json"

        /** The resolution the map opens at. Coarser and finer ones are optional assets. */
        const val DEFAULT_RES: Int = 5

        private const val ASSET_PREFIX = "h3_res="

        private fun mean(propertyId: String) = "${propertyId}__mean"

        /**
         * Parses [json], resolving relative asset hrefs against [root], the folder of the file. An
         * http or https href is kept as it is. An asset whose key is not `h3_res=<n>`, or whose href
         * has another scheme, is skipped.
         *
         * @throws IllegalArgumentException when [json] is not a Collection.
         */
        fun parse(json: String, root: String): DiscoverCollection {
            val stac = StationCollection.parse(json)
            val dto = try {
                FeedPaths.json.decodeFromString<Dto>(json)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException(e.message, e)
            }
            val base = root.trimEnd('/')
            val end = dto.extent?.temporal?.interval?.firstOrNull()?.getOrNull(1)
            return DiscoverCollection(
                stac = stac,
                asOf = end?.let { LocalDate.parse(it.take(ISO_DATE_LENGTH)) },
                generation = dto.updated,
                cells = dto.assets.mapNotNull { (key, asset) ->
                    val res = key.removePrefix(ASSET_PREFIX).takeIf { key.startsWith(ASSET_PREFIX) }?.toIntOrNull()
                    val url = resolve(base, asset.href)
                    if (res == null || url == null) null else res to url
                }.toMap(),
            )
        }

        private fun resolve(base: String, href: String): String? = when {
            href.startsWith("https://") || href.startsWith("http://") -> href
            "://" in href || href.startsWith("/") || href.split('/').any { it == ".." } -> null
            else -> "$base/${href.removePrefix("./")}"
        }

        private const val ISO_DATE_LENGTH = 10

        /**
         * The scales the app ships, for a feed that has no Collection yet (the bundled sample and the
         * OpenAQ manifest feed). PM2.5 and PM10 on the WHO 2021 guideline and interim targets, the
         * same edges, colours and step names as [WhoGuidelines] and the app's step strings.
         */
        val BUNDLED: DiscoverCollection by lazy { parse(bundledJson(), root = "") }

        private fun bundledJson(): String {
            fun scale(edges: List<String>, guideline: String): String {
                val uppers = edges + "null"
                val steps = uppers.indices.joinToString(",") { i ->
                    """{"upper":${uppers[i]},"label":{"en":"${WHO_EN[i]}","ar":"${WHO_AR[i]}"},"color_hint":"${WHO_COLORS[i]}"}"""
                }
                return """{"source":"${WhoGuidelines.URL}","unit":"µg/m³","guideline":$guideline,"steps":[$steps]}"""
            }
            return """
                {"type":"Collection","id":"discover-cells-bundled",
                 "opensensor:scales":{
                   "who-2021-pm25":${scale(listOf("15", "25", "37.5", "50", "75"), "15")},
                   "who-2021-pm10":${scale(listOf("45", "50", "75", "100", "150"), "45")}},
                 "item_assets":{"data":{"table:columns":[
                   {"name":"pm25__mean","opensensor:scale":"who-2021-pm25"},
                   {"name":"pm10__mean","opensensor:scale":"who-2021-pm10"}]}}}
            """.trimIndent()
        }

        private val WHO_COLORS = listOf("C6EBA8", "F5D75E", "F2A14A", "DC5A4A", "9A3F8E", "5C1A33")
        private val WHO_EN = listOf(
            "Within the WHO guideline",
            "Within WHO interim target 4",
            "Within WHO interim target 3",
            "Within WHO interim target 2",
            "Within WHO interim target 1",
            "Above every WHO target",
        )
        private val WHO_AR = listOf(
            "ضمن إرشاد منظمة الصحة العالمية",
            "ضمن الهدف المرحلي 4 لمنظمة الصحة العالمية",
            "ضمن الهدف المرحلي 3 لمنظمة الصحة العالمية",
            "ضمن الهدف المرحلي 2 لمنظمة الصحة العالمية",
            "ضمن الهدف المرحلي 1 لمنظمة الصحة العالمية",
            "فوق كل أهداف منظمة الصحة العالمية",
        )
    }
}
