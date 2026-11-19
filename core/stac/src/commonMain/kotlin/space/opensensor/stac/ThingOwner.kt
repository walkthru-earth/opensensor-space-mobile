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
import space.opensensor.model.DisplayGroup
import space.opensensor.model.ThingSite
import space.opensensor.stac.ProfileRules.isI18n
import space.opensensor.stac.ProfileRules.isId
import space.opensensor.stac.ProfileRules.isScale
import space.opensensor.stac.ProfileRules.isText

/**
 * One STAC provider of a Thing Collection, with the four fields STAC 1.1.0 defines. A null [roles],
 * [url] or [description] is left out, so a provider is copied as the owner gave it.
 */
data class StacProvider(
    val name: String,
    val roles: List<String>? = null,
    val url: String? = null,
    val description: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("name", name)
        description?.let { put("description", it) }
        roles?.let { putJsonArray("roles") { it.forEach { role -> add(JsonPrimitive(role)) } } }
        url?.let { put("url", it) }
    }

    companion object {
        /** The keys a provider may have. [ThingOwner.parse] refuses any other, so none is dropped. */
        val KEYS = setOf("name", "description", "roles", "url")
    }
}

/**
 * The display hints the owner adds to or replaces in one template column, key by key, as the profile
 * lets an owner do. [hints] holds only keys in [KEYS], each of the type the profile gives it. A group is
 * one the pinned profile lists ([DisplayGroup.isKnown]), so the phone never writes a group it cannot read.
 */
data class ColumnLabel(val hints: JsonObject) {
    constructor(title: String? = null, scale: String? = null) : this(
        buildJsonObject {
            title?.let { put("title", it) }
            scale?.let { put(ThingCollections.SCALE, it) }
        },
    )

    init {
        hints.forEach { (key, value) ->
            val ok = when (key) {
                "title", "description" -> value.isText()
                GROUP -> value.isText() && DisplayGroup((value as JsonPrimitive).content).isKnown
                ThingCollections.SCALE -> value.isId()
                DECIMALS -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.let(::decimalsOf) != null
                TITLE_I18N, DESCRIPTION_I18N -> value.isI18n()
                else -> throw IllegalArgumentException("a column adds $key, which is not a display hint")
            }
            require(ok) { "the display hint $key has a value of another type" }
        }
    }

    fun json(): JsonObject = hints

    companion object {
        const val DECIMALS = "opensensor:decimals"
        const val GROUP = "opensensor:group"
        const val TITLE_I18N = "opensensor:title_i18n"
        const val DESCRIPTION_I18N = "opensensor:description_i18n"

        private val JSON_NUMBER = Regex("""-?(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?""")

        /**
         * The decimals a JSON number gives, when it is an integer from 0 to 9 however it is spelled, as
         * `1`, `1.0`, `1e0` or `0.5e1`. It reads the digits, not a Double, so `1.0000000000000001` is
         * not rounded to 1. Anything else is null.
         */
        private fun decimalsOf(number: String): Int? {
            val (whole, fraction, exponent) = JSON_NUMBER.matchEntire(number)?.destructured ?: return null
            val digits = whole + fraction
            if (digits.all { it == '0' }) return 0
            if (number.startsWith('-')) return null
            val shift = if (exponent.isEmpty()) 0L else exponent.toLongOrNull() ?: return null
            val point = whole.length + shift
            if (point < 0 || point > digits.length) return null
            if (digits.drop(point.toInt()).any { it != '0' }) return null
            return digits.take(point.toInt()).trimStart('0').ifEmpty { "0" }.takeIf { it.length == 1 }?.toInt()
        }
    }
}

/**
 * The pinned profile's rules for what an owner writes, from stac-opensensor `schema.json`. The phone
 * checks them before it copies a value into a Collection, so it never publishes one the profile refuses.
 */
private object ProfileRules {
    /** The profile's `id`, which a scale id must be. */
    private val ID = Regex("""[a-z0-9][a-z0-9._-]*""")

    /** The profile's language code, the key of an i18n map. */
    private val LANGUAGE = Regex("""[a-z]{2,3}(-[A-Za-z0-9]+)*""")

    /** The profile's ISO 8601 duration, the period of a scale. */
    private val PERIOD = Regex("""^P(?!$)([0-9]+Y)?([0-9]+M)?([0-9]+W)?([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$""")

    /** An absolute URI, a scheme then RFC 3986 characters, where each `%` starts two hex digits. */
    private val URI = Regex("""[A-Za-z][A-Za-z0-9+.-]*:(?:[A-Za-z0-9\-._~!$&'()*+,;=:/?#\[\]@]|%[0-9A-Fa-f]{2})+""")

    private val COLOR_HINT = Regex("""[0-9A-F]{6}""")
    private val SCALE_KEYS = setOf("source", "unit", "period", "guideline", "steps")
    private val STEP_KEYS = setOf("upper", "label", "color_hint")

    /** A nonempty string. The profile gives every text hint `minLength` 1, so an empty one would replace the template's. */
    fun JsonElement.isText() = this is JsonPrimitive && isString && content.isNotEmpty()

    fun JsonElement.isId() = isText() && ID.matches((this as JsonPrimitive).content)

    /** A map from language code to text, with at least one entry. */
    fun JsonElement.isI18n() = this is JsonObject && isNotEmpty() && all { (language, text) -> LANGUAGE.matches(language) && text.isText() }

    /** A scale as the profile defines it, with a source, a period and at least one step. */
    fun JsonElement.isScale(): Boolean {
        if (this !is JsonObject || !SCALE_KEYS.containsAll(keys)) return false
        val steps = this["steps"] as? JsonArray ?: return false
        return this["source"].let { it != null && it.isText() && URI.matches((it as JsonPrimitive).content) } &&
            this["period"].let { it != null && it.isText() && PERIOD.matches((it as JsonPrimitive).content) } &&
            this["unit"].let { it == null || (it is JsonPrimitive && it.isString) } &&
            this["guideline"].let { it == null || it.isNumber() } &&
            steps.isNotEmpty() && steps.all { it.isStep() }
    }

    private fun JsonElement.isStep(): Boolean {
        if (this !is JsonObject || !STEP_KEYS.containsAll(keys)) return false
        val upper = this["upper"] ?: return false
        val label = this["label"] ?: return false
        return (upper == JsonNull || upper.isNumber()) &&
            (label.isText() || label.isI18n()) &&
            this["color_hint"].let { it == null || (it.isText() && COLOR_HINT.matches((it as JsonPrimitive).content)) }
    }

    private fun JsonElement.isNumber() = this is JsonPrimitive && !isString && content.toDoubleOrNull() != null
}

/**
 * The owner config of one Thing, the `owner` input of `buildCollection` in stac-opensensor
 * `scripts/canonical.mjs` and the shape of `fixtures/thing=<uuid>/owner.json` (v0.1.0, commit
 * e9981360c2229a98d5659ef0038a3b6114953f73). Everything in a Thing Collection that is not fixed by the
 * profile, its template or its footer comes from here.
 *
 * [title], [description] and the producer in [providers] come from the owner site the board keeps
 * (firmware #148), which the phone reads and edits as [ThingSite]. [of] maps one to the other, so the
 * site stays the one record of what the owner typed. [prefix], the host provider, [columns], [primary],
 * [scales] and [contacts] belong to the upload target and the owner's display hints, which no board key
 * holds yet. A null [title] or [description] is left out of the Collection. [providers] are copied as
 * given. [primary] wins over the template's. [scales] overlay the template's, and only the ids a column
 * names are listed. [contacts] adds the Contacts extension when it is not null, even empty.
 */
data class ThingOwner(
    val title: String?,
    val description: String?,
    val providers: List<StacProvider>,
    val prefix: String,
    val columns: Map<String, ColumnLabel> = emptyMap(),
    val primary: String? = null,
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

        /**
         * The owner config [json] in the shape of `owner.json`. Throws [IllegalArgumentException] when it
         * is not one, including a `providers`, `columns`, `primary`, `scales`, `contacts` or `roles` of
         * another JSON type, a column key that is not a display hint, an empty `primary` or a scale the
         * profile refuses. A member that is missing or JSON null counts as not given.
         */
        fun parse(json: String): ThingOwner {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("owner config is not an object")
            val providers = root.member<JsonArray>("providers", "an array").orEmpty().map { p ->
                val o = p as? JsonObject ?: throw IllegalArgumentException("a provider is not an object")
                require(o.keys.all { it in StacProvider.KEYS }) { "a provider has a key STAC does not define" }
                StacProvider(
                    name = requireNotNull(o.string("name")) { "a provider has no name" },
                    roles = o.member<JsonArray>("roles", "an array")
                        ?.map { requireNotNull((it as? JsonPrimitive)?.takeIf { r -> r.isString }?.content) { "a role is not a string" } },
                    url = o.string("url"),
                    description = o.string("description"),
                )
            }
            val columns = root.member<JsonObject>("columns", "an object").orEmpty().mapValues { (name, c) ->
                val o = c as? JsonObject ?: throw IllegalArgumentException("column $name is not an object")
                ColumnLabel(JsonObject(o.filterValues { it != JsonNull }))
            }
            return ThingOwner(
                title = root.string("title"),
                description = root.string("description"),
                providers = providers,
                prefix = requireNotNull(root.string("prefix")) { "owner config has no prefix" },
                columns = columns,
                primary = root.string("primary")?.also { require(it.isNotEmpty()) { "primary is empty" } },
                scales = root.member<JsonObject>("scales", "an object")?.also { scales ->
                    scales.forEach { (id, scale) ->
                        require(JsonPrimitive(id).isId() && scale.isScale()) { "scale $id is not one the profile allows" }
                    }
                },
                contacts = root.member<JsonArray>("contacts", "an array"),
            )
        }

        private fun String?.orBlank(): String? = this?.takeIf { it.isNotBlank() }

        /** The member [key] as a [T], null when it is missing or JSON null. Throws when it is another type. */
        private inline fun <reified T : JsonElement> JsonObject.member(key: String, kind: String): T? = when (val v = this[key]) {
            null, JsonNull -> null
            is T -> v
            else -> throw IllegalArgumentException("$key is not $kind")
        }

        private fun JsonObject.string(key: String): String? = when (val v: JsonElement? = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key is not a string")
            else -> throw IllegalArgumentException("$key is not a string")
        }
    }
}
