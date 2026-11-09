package space.opensensor.publicfeed

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import space.opensensor.model.H3Cell
import space.opensensor.model.Scale
import space.opensensor.model.ThingCollection
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * The discover Collection, `discover/collection.json` (docs/devices-and-discovery.md). A STAC
 * Collection of H3 aggregates with one cells asset per resolution ([cellsResolution]). Its
 * `item_assets.data.table:columns` name
 * `<id>__n`, `__mean`, `__min` and `__max` per property. The `__mean` column carries the property's
 * `title` and, when one applies, its `opensensor:scale`, a key of `opensensor:scales`.
 *
 * [asOf] is the UTC date of the end of `extent.temporal.interval`, the newest complete day. [generation]
 * is `updated`, which changes whenever the files are republished in place. [cells] maps each
 * resolution to the URL of its cells file. A zoom whose resolution has no asset reads the nearest one
 * that has ([resolutionNear]).
 */
class DiscoverCollection private constructor(
    private val stac: ThingCollection,
    val asOf: LocalDate?,
    val generation: String?,
    val cells: Map<Int, String>,
) {
    /**
     * The listed resolution nearest [res] that the map can draw ([ViewportCover.MIN_RES] to
     * [ViewportCover.MAX_RES]). Between two equally near, the one nearer [DEFAULT_RES], then the
     * coarser. Null when no drawable resolution is listed.
     */
    fun resolutionNear(res: Int): Int? = cells.keys
        .filter { it in ViewportCover.MIN_RES..ViewportCover.MAX_RES }
        .minWithOrNull(compareBy<Int>({ abs(it - res) }, { abs(it - DEFAULT_RES) }, { it }))

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

        /**
         * The default H3 resolution of every Thing, fixed or moving. It is the tie break of [resolutionNear],
         * so between two drawable resolutions equally near the zoom the finer one wins.
         */
        const val DEFAULT_RES: Int = 8

        /** The key forms of a cells asset. `cells_r<n>` is the live feed's, `h3_res=<n>` the older one. */
        private val CELLS_KEYS = listOf("cells_r", "h3_res=")

        /** The Hive path segment `h3_res=<n>` in a cells asset's href. */
        private const val HREF_SEGMENT = "h3_res="

        private const val PARQUET = "application/vnd.apache.parquet"

        private fun mean(propertyId: String) = "${propertyId}__mean"

        /**
         * Parses [json], resolving relative asset hrefs against [root], the folder of the file. An
         * absolute href is kept only when it is https on the same host as an https [root], so a
         * Collection cannot send the app to another host, to cleartext or to a local address. Any
         * other asset, one that is not a cells asset ([cellsResolution]) and each asset of a
         * resolution listed more than once are dropped alone. A scale in
         * `opensensor:scales` that does not parse, or that [StepScale.of] refuses, is dropped alone,
         * so its property falls back to the neutral ramp and the rest of the Collection stands.
         *
         * @throws IllegalArgumentException when [json] is not a Collection.
         */
        fun parse(json: String, root: String): DiscoverCollection {
            val stac = ThingCollection.parse(withoutBrokenScales(json))
            val dto = try {
                FeedPaths.json.decodeFromString<Dto>(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException(e.message, e)
            }
            val base = root.trimEnd('/')
            val end = dto.extent?.temporal?.interval?.firstOrNull()?.getOrNull(1)
            val listed = dto.assets.mapNotNull { (key, asset) -> cellsResolution(key, asset.href, asset.mediaType)?.let { it to asset.href } }
            val times = listed.groupingBy { it.first }.eachCount()
            return DiscoverCollection(
                stac = stac,
                asOf = end?.let { LocalDate.parse(it.take(ISO_DATE_LENGTH)) },
                generation = dto.updated,
                cells = listed.filter { times[it.first] == 1 }.mapNotNull { (res, href) -> resolve(base, href)?.let { res to it } }.toMap(),
            )
        }

        /**
         * The H3 resolution of a cells asset, or null when the asset is not one. The key names it,
         * `cells_r<n>` or `h3_res=<n>`, and a key in either form whose `<n>` is not a resolution
         * drops the asset. Any other key falls back to the one `h3_res=<n>` path segment of the href,
         * and only for a Parquet asset, so `annual`, `daily`, `hourly`, `stations` and `manifest`
         * are never cells. When the href names a resolution too it must be the key's, or the asset
         * is ambiguous and dropped. `<n>` is plain decimal, 0 to [H3Cell.MAX_RES], with no sign and
         * no leading zero. stac-opensensor#6 has not fixed the key yet, so both forms are read.
         */
        private fun cellsResolution(key: String, href: String, mediaType: String?): Int? {
            val segments = href.substringBefore('?').substringBefore('#').split('/').filter { it.startsWith(HREF_SEGMENT) }
            val fromHref = segments.singleOrNull()?.let { resolutionOf(it.removePrefix(HREF_SEGMENT)) }
            val form = CELLS_KEYS.firstOrNull { key.startsWith(it) } ?: return fromHref.takeIf { mediaType == PARQUET }
            val fromKey = resolutionOf(key.removePrefix(form)) ?: return null
            return fromKey.takeIf { segments.isEmpty() || fromHref == fromKey }
        }

        private fun resolutionOf(digits: String): Int? = digits
            .takeIf { it.length in 1..2 && it.all { c -> c in '0'..'9' } && (it.length == 1 || it[0] != '0') }
            ?.toInt()
            ?.takeIf { it <= H3Cell.MAX_RES }

        /**
         * [json] without the scales that do not parse on their own or that [StepScale.of] refuses (too
         * many steps, edges out of order, out of bounds or not finite). Each scale is checked from the
         * tree parsed once here, so the work grows with the number of scales, not its square. Only the
         * first [MAX_SCALES] entries are read, the rest are dropped like broken ones. Any other fault
         * is left for the full parse to report.
         */
        private fun withoutBrokenScales(json: String): String {
            val tree = try {
                FeedPaths.json.parseToJsonElement(json) as? JsonObject
            } catch (e: SerializationException) {
                throw IllegalArgumentException(e.message, e)
            } ?: return json
            val scales = tree[SCALES] as? JsonObject ?: return json
            val kept = scales.entries.take(MAX_SCALES).filter { (id, scale) ->
                try {
                    StepScale.of(id, Scale.parse(id, scale)) != null
                } catch (_: IllegalArgumentException) {
                    false
                }
            }.associate { it.key to it.value }
            return if (kept.size == scales.size) json else JsonObject(tree + (SCALES to JsonObject(kept))).toString()
        }

        /**
         * The most `opensensor:scales` entries read. A Collection has one per health scale, a handful
         * (two bundled today), so 64 leaves room for every property while a feed listing thousands
         * cannot stall the parse. Entries past it are dropped and their properties fall back to the
         * neutral ramp.
         */
        const val MAX_SCALES: Int = 64

        private const val SCALES = "opensensor:scales"

        private fun resolve(base: String, href: String): String? = when {
            href.startsWith(HTTPS) -> href.takeIf { authority(it) != null && authority(it) == authority(base) }
            ':' in href || '\\' in href || href.startsWith("/") || href.split('/').any { it == ".." } -> null
            else -> "$base/${href.removePrefix("./")}"
        }

        /** The host and port of an https [url], or null for any other URL or one with user info. */
        private fun authority(url: String): String? {
            if (!url.startsWith(HTTPS)) return null
            val rest = url.removePrefix(HTTPS)
            val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            return rest.take(end).lowercase().takeIf { it.isNotEmpty() && '@' !in it && '\\' !in it }
        }

        private const val HTTPS = "https://"

        private const val ISO_DATE_LENGTH = 10

        /**
         * The scales the app's families ship, for a feed that has no Collection yet (the bundled
         * sample and the OpenAQ manifest feed). It names no scale until an app entry point installs
         * the families' collection ([installBundled]).
         */
        val BUNDLED: DiscoverCollection get() = bundled

        @Volatile
        private var bundled: DiscoverCollection = parse("""{"type":"Collection","id":"discover-cells-bundled"}""", root = "")

        /** Makes the Collection [json] the [BUNDLED] one. Each app entry point calls it once at launch. */
        fun installBundled(json: String) {
            bundled = parse(json, root = "")
        }
    }
}
