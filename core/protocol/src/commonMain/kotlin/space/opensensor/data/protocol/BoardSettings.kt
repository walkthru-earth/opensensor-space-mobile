package space.opensensor.data.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The current values on GET_CONFIG page [Request.GetConfigPage.BOARD_SETTINGS], keyed by their
 * `SET_CONFIG` key, so a declared [ConfigField] finds its value by [ConfigField.key].
 *
 * The page nests each key under its prefix, `{"panel":{"theme":"dark"}}` reads as `panel.theme`.
 * A string or number reads as its text and a boolean as `1` or `0`, the `SET_CONFIG` spelling.
 * A null or an array is no value. A key under [ConfigFields.RESERVED_PREFIXES] or in
 * [ConfigFields.SECRET_KEYS] is dropped, those come from their own pages.
 */
data class BoardSettings(val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]

    companion object {
        /** The deepest nesting read, so a hostile page cannot recurse without end. */
        private const val MAX_DEPTH = 4

        val EMPTY = BoardSettings(emptyMap())

        /** Parses the page JSON. Throws like [DeviceConfig.parse] when it is not a JSON object. */
        fun parse(text: String): BoardSettings {
            val root = WireJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("board settings page is not a JSON object")
            val out = LinkedHashMap<String, String>()
            flatten("", root, 1, out)
            return BoardSettings(out.filterKeys { !ConfigFields.isReserved(it) && it !in ConfigFields.SECRET_KEYS })
        }

        private fun flatten(prefix: String, obj: JsonObject, depth: Int, out: MutableMap<String, String>) {
            for ((name, element) in obj) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when (element) {
                    is JsonObject -> if (depth < MAX_DEPTH) flatten(key, element, depth + 1, out)
                    is JsonPrimitive -> text(element)?.let { out[key] = it }
                    is JsonArray -> Unit
                }
            }
        }

        private fun text(p: JsonPrimitive): String? = when {
            p is JsonNull -> null
            p.isString -> p.content
            else -> p.booleanOrNull?.let { if (it) "1" else "0" } ?: p.content
        }
    }
}
