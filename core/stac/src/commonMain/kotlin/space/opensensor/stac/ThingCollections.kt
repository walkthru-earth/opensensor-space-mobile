package space.opensensor.stac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.model.FileChecksum

/**
 * What a Thing Collection reads from the newest footer of its Thing. [templateHref] and
 * [templateSha256] are the footer `template.href` and `template.sha256`, the board template the files
 * were written against. [templateSha256] must be SHA-256 hex, or the constructor throws
 * [IllegalArgumentException].
 */
data class CollectionFooter(
    val thingId: String,
    val mobility: OpenSensorFooter.Mobility,
    val templateHref: String,
    val templateSha256: String,
) {
    init {
        require(FileChecksum.isSha256Hex(templateSha256.lowercase())) { "template.sha256 is not a SHA-256 hex digest" }
    }

    companion object {
        /**
         * The Collection fields of a parsed [footer] whose JSON names [templateHref] as `template.href`,
         * or null when the footer has no Thing id, mobility or SHA-256 hex template hash.
         */
        fun of(footer: OpenSensorFooter, templateHref: String): CollectionFooter? = CollectionFooter(
            thingId = footer.thingId ?: return null,
            mobility = footer.mobility ?: return null,
            templateHref = templateHref,
            templateSha256 = footer.templateSha256?.takeIf { FileChecksum.isSha256Hex(it.lowercase()) } ?: return null,
        )
    }
}

/**
 * The upload ledger of a Thing, what its Collection extent covers. [start] is the earliest
 * `start_datetime` of its uploaded Items and [bbox] the union of their boxes, west, south, east, north.
 */
data class CollectionLedger(val start: String, val bbox: List<Double>) {
    init {
        require(bbox.size == 4) { "a bbox has four numbers" }
    }

    companion object {
        /**
         * The ledger of the uploaded [items], as stac-opensensor `scripts/check-profile.mjs` computes it.
         * The earliest start is the least RFC 3339 text, which orders as time because every start is
         * UTC with a `Z`. Null when there is no Item, or an Item has no start or no bbox.
         */
        fun of(items: List<JsonObject>): CollectionLedger? {
            if (items.isEmpty()) return null
            val starts = items.map { ((it["properties"] as? JsonObject)?.get("start_datetime") as? JsonPrimitive)?.content ?: return null }
            val boxes = items.map { item -> (item["bbox"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null } ?: return null }
            if (boxes.any { it.size != 4 }) return null
            return CollectionLedger(
                start = starts.min(),
                bbox = listOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] }),
            )
        }
    }
}

/**
 * The Thing Collection, `thing=<uuid>/collection.json`, in the canonical form of the stac-opensensor
 * v0.1.0 profile (tag v0.1.0, commit 83787768cc8451109d48a03d4ef5493ffcd403dd), as `buildCollection`
 * in its `scripts/canonical.mjs` writes it. The writer of a Thing's files writes its Collection, so the
 * board writes a board Thing's Collection and the phone checks it, and the phone writes one only for a
 * Thing it records itself. A Collection built here must equal the writer's as JSON values
 * ([CanonicalJson.difference]). Pure. It never uploads, writes or logs, and names H3 derived boxes
 * only, never a device position.
 */
object ThingCollections {
    const val STAC_VERSION = FileItems.STAC_VERSION
    const val LICENSE = "CC-BY-4.0"
    const val CONTACTS_EXTENSION = "https://stac-extensions.github.io/contacts/v0.1.1/schema.json"

    /** The column key that names an owner scale. */
    const val SCALE = "opensensor:scale"

    /** Every extension a Collection can list, in the profile's order. Without contacts it leaves out [CONTACTS_EXTENSION]. */
    val EXTENSIONS = listOf(
        "https://stac-extensions.github.io/table/v1.3.0/schema.json",
        "https://stac-extensions.github.io/cf/v1.0.0/schema.json",
        "https://stac-extensions.github.io/classification/v2.0.0/schema.json",
        "https://stac-extensions.github.io/file/v2.1.0/schema.json",
        "https://schemas.portolan-sdi.org/incubating/partition/v1.0.0/schema.json",
        CONTACTS_EXTENSION,
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/schema.json",
        "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/profile.json",
    )

    /** The Hive keys of `thing=<uuid>/year=YYYY/month=MM/day=DD/`, in path order. */
    val PARTITION_KEYS = listOf("year", "month", "day")

    private const val LICENSE_HREF = "https://creativecommons.org/licenses/by/4.0/"
    private const val CATALOG_HREF = "../catalog.json"
    private const val JSON_TYPE = "application/json"
    private const val TEXT_TYPE = "text/plain"
    private const val DESCRIBED_BY = "describedby"

    /**
     * The Collection of the Thing the [footer] names, from its board [template] as the template file's
     * JSON, the owner config [owner] and the upload [ledger]. Columns are the template's, in its order,
     * each with the owner's [ColumnLabel]. `opensensor:scales` is listed only when a column names a
     * scale and the owner has [ThingOwner.scales], as `buildCollection` drops an undefined value.
     * Providers are copied as the owner gives them. Throws [IllegalArgumentException] when [template] lacks a part the Collection copies.
     */
    fun build(template: JsonObject, footer: CollectionFooter, owner: ThingOwner, ledger: CollectionLedger): JsonObject {
        val data = requireNotNull((template["item_assets"] as? JsonObject)?.get("data") as? JsonObject) { "template has no item_assets.data" }
        val columns = requireNotNull(data["table:columns"] as? JsonArray) { "template has no table:columns" }.map { column ->
            val c = requireNotNull(column as? JsonObject) { "a template column is not an object" }
            val label = (c["name"] as? JsonPrimitive)?.content?.let { owner.columns[it] }
            label?.let { JsonObject(c + it.json()) } ?: c
        }
        val usesScales = columns.any { SCALE in it }
        val dictionary = requireNotNull(
            (template["links"] as? JsonArray)?.firstOrNull { link ->
                link is JsonObject && link.text("rel") == DESCRIBED_BY && link.text("type") == TEXT_TYPE
            },
        ) { "template has no describedby text/plain link" }
        return buildJsonObject {
            put("type", "Collection")
            put("stac_version", STAC_VERSION)
            putJsonArray("stac_extensions") {
                EXTENSIONS.filter { owner.contacts != null || it != CONTACTS_EXTENSION }.forEach { add(JsonPrimitive(it)) }
            }
            put("id", footer.thingId)
            owner.title?.let { put("title", it) }
            owner.description?.let { put("description", it) }
            put("license", LICENSE)
            put("keywords", template.part("keywords"))
            put("instruments", template.part("instruments"))
            put("providers", JsonArray(owner.providers.map { it.json() }))
            owner.contacts?.let { put("contacts", it) }
            putJsonObject("extent") {
                putJsonObject("spatial") { putJsonArray("bbox") { addJsonArray { ledger.bbox.forEach { add(JsonPrimitive(it)) } } } }
                putJsonObject("temporal") {
                    putJsonArray("interval") {
                        addJsonArray {
                            add(JsonPrimitive(ledger.start))
                            add(JsonNull)
                        }
                    }
                }
            }
            put("opensensor:sample_interval_s", template.part("opensensor:sample_interval_s"))
            put("opensensor:mobility", footer.mobility.wire)
            put("partition:scheme", "hive")
            put("partition:strategy", "temporal")
            putJsonArray("partition:keys") {
                PARTITION_KEYS.forEach { key ->
                    addJsonObject {
                        put("name", key)
                        put("type", "int32")
                        put("description", "UTC $key of the file window, zero padded on disk")
                    }
                }
            }
            put("partition:glob", "s3://${owner.prefix}/thing=${footer.thingId}/year=*/month=*/day=*/*.parquet")
            if (usesScales) owner.scales?.let { put("opensensor:scales", it) }
            putJsonObject("item_assets") { put("data", JsonObject(data + ("table:columns" to JsonArray(columns)))) }
            putJsonArray("links") {
                link("root", CATALOG_HREF, JSON_TYPE)
                link("parent", CATALOG_HREF, JSON_TYPE)
                add(dictionary)
                link(DESCRIBED_BY, footer.templateHref, JSON_TYPE, "Board template") { put("file:checksum", FileChecksum.multihash(footer.templateSha256)) }
                link("license", LICENSE_HREF, "text/html", "CC BY 4.0")
            }
        }
    }

    private fun JsonObject.part(key: String): JsonElement = requireNotNull(this[key]) { "template has no $key" }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun kotlinx.serialization.json.JsonArrayBuilder.link(
        rel: String,
        href: String,
        type: String,
        title: String? = null,
        more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = addJsonObject {
        put("rel", rel)
        put("href", href)
        put("type", type)
        title?.let { put("title", it) }
        more()
    }
}
