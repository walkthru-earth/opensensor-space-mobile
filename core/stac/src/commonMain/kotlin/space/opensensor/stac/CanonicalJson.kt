package space.opensensor.stac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * Equality under the canonical form of the stac-opensensor v0.1.0 profile, as `differences` in
 * `scripts/canonical.mjs` compares two documents. Two documents are equal when they are the same JSON
 * value. Object key order does not matter, array order does. Numbers compare as doubles, so `10` equals
 * `10.0`, and a string never equals a number. Under `geometry`, `bbox` and `extent.spatial` numbers may
 * differ by [COORDINATE_TOLERANCE] degrees, because H3 libraries differ in the last bits.
 */
object CanonicalJson {
    /** Degrees two coordinates may differ by. */
    const val COORDINATE_TOLERANCE = 1e-9

    /**
     * The path of the first place [expected] and [actual] differ, such as `$.links[2].href`, or null
     * when they are canonically equal.
     */
    fun difference(expected: JsonElement, actual: JsonElement): String? = differ(expected, actual, ROOT)

    private fun differ(expected: JsonElement, actual: JsonElement, at: String): String? = when {
        expected is JsonObject && actual is JsonObject ->
            if (expected.keys != actual.keys) {
                at
            } else {
                expected.keys.firstNotNullOfOrNull { differ(expected.getValue(it), actual.getValue(it), "$at.$it") }
            }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) {
                at
            } else {
                expected.indices.firstNotNullOfOrNull { differ(expected[it], actual[it], "$at[$it]") }
            }
        expected is JsonNull || actual is JsonNull -> at.takeIf { expected != actual }
        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val x = expected.doubleOrNull.takeIf { !expected.isString }
            val y = actual.doubleOrNull.takeIf { !actual.isString }
            when {
                x != null && y != null -> at.takeIf { abs(x - y) > toleranceAt(at) }
                else -> at.takeIf { expected.isString != actual.isString || expected.content != actual.content }
            }
        }
        else -> at
    }

    private fun toleranceAt(at: String): Double =
        if (NEAR.any { at == it || at.startsWith("$it.") || at.startsWith("$it[") }) COORDINATE_TOLERANCE else 0.0

    private const val ROOT = "$"
    private val NEAR = listOf("$ROOT.geometry", "$ROOT.bbox", "$ROOT.extent.spatial")
}
