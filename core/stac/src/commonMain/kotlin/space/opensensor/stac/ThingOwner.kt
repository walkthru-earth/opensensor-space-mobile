package space.opensensor.stac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import space.opensensor.model.ThingSite

/** One STAC provider of a Thing Collection. */
data class StacProvider(val name: String, val roles: List<String>, val url: String? = null) {
    fun json(): JsonObject = buildJsonObject {
        put("name", name)
        putJsonArray("roles") { roles.forEach { add(JsonPrimitive(it)) } }
        url?.let { put("url", it) }
    }
}

/** What the owner adds to one template column, the only two keys the profile lets an owner add. */
data class ColumnLabel(val title: String? = null, val scale: String? = null) {
    fun json(): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        scale?.let { put(ThingCollections.SCALE, it) }
    }
}

/**
 * The owner config of one Thing, the `owner` input of `buildCollection` in stac-opensensor
 * `scripts/canonical.mjs` and the shape of `fixtures/thing=<uuid>/owner.json` (v0.1.0, commit
 * 83787768cc8451109d48a03d4ef5493ffcd403dd). Everything in a Thing Collection that is not fixed by the
 * profile, its template or its footer comes from here.
 *
 * [title], [description] and the producer in [providers] come from the owner site the board keeps
 * (firmware #148), which the phone reads and edits as [ThingSite]. [of] maps one to the other, so the
 * site stays the one record of what the owner typed. [prefix], the host provider, [columns], [scales]
 * and [contacts] belong to the upload target and the owner's labels, which no board key holds yet.
 * A null [title] or [description] is left out of the Collection. [contacts] adds the Contacts
 * extension when it is not null, even empty.
 */
data class ThingOwner(
    val title: String?,
    val description: String?,
    val providers: List<StacProvider>,
    val prefix: String,
    val columns: Map<String, ColumnLabel> = emptyMap(),
    val scales: JsonObject? = null,
    val contacts: JsonArray? = null,
) {
    companion object {
        const val PRODUCER = "producer"
        const val LICENSOR = "licensor"
        const val HOST = "host"

        /**
         * The owner config of a Thing whose owner [site] the board reported, published under the
         * bucket and [prefix] of its upload target, which [host] runs. An empty or missing site value
         * is left out. `owner.provider` is the producer and licensor, with `owner.url` as its link, as
         * the firmware `SET_CONFIG` table names them. A site with no provider lists only [host], and the
         * profile wants the owner as producer, so the Collection is complete once the owner names one.
         * `thing.deployment_id` stays in the file footers, a Collection never names it.
         */
        fun of(site: ThingSite, prefix: String, host: StacProvider): ThingOwner {
            val producer = site.provider.orBlank()?.let { StacProvider(it, listOf(PRODUCER, LICENSOR), site.url.orBlank()) }
            return ThingOwner(
                title = site.title.orBlank(),
                description = site.description.orBlank(),
                providers = listOfNotNull(producer, host),
                prefix = prefix,
            )
        }

        /** The owner config [json] in the shape of `owner.json`. Throws [IllegalArgumentException] when it is not one. */
        fun parse(json: String): ThingOwner {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("owner config is not an object")
            val providers = (root["providers"] as? JsonArray ?: JsonArray(emptyList())).map { p ->
                val o = p as? JsonObject ?: throw IllegalArgumentException("a provider is not an object")
                StacProvider(
                    name = requireNotNull(o.string("name")) { "a provider has no name" },
                    roles = (o["roles"] as? JsonArray)?.map { requireNotNull((it as? JsonPrimitive)?.takeIf { r -> r.isString }?.content) }.orEmpty(),
                    url = o.string("url"),
                )
            }
            val columns = (root["columns"] as? JsonObject).orEmpty().mapValues { (name, c) ->
                val o = c as? JsonObject ?: throw IllegalArgumentException("column $name is not an object")
                require(o.keys.all { it == "title" || it == ThingCollections.SCALE }) { "column $name adds a key the profile does not allow" }
                ColumnLabel(title = o.string("title"), scale = o.string(ThingCollections.SCALE))
            }
            return ThingOwner(
                title = root.string("title"),
                description = root.string("description"),
                providers = providers,
                prefix = requireNotNull(root.string("prefix")) { "owner config has no prefix" },
                columns = columns,
                scales = root["scales"] as? JsonObject,
                contacts = root["contacts"] as? JsonArray,
            )
        }

        private fun String?.orBlank(): String? = this?.takeIf { it.isNotBlank() }

        private fun JsonObject.string(key: String): String? = when (val v: JsonElement? = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key is not a string")
            else -> throw IllegalArgumentException("$key is not a string")
        }
    }
}
