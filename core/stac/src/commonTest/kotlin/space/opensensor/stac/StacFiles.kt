package space.opensensor.stac

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bytes of [path] under `src/commonTest/fixtures/stac-opensensor`, the files copied byte for byte
 * from stac-opensensor v0.1.0. The JVM and the iOS simulator both read them from the host file system.
 */
expect fun stacFile(path: String): ByteArray

/**
 * The fixture template the stac-opensensor footer [footer] names, `templates/<board>/<dictionary>.json`.
 * The v0.1.0 examples were built from these bytes, which can be older than the bundled template of the
 * same board.
 */
fun stacTemplatePath(footer: JsonObject): String {
    val board = footer["thing"]!!.jsonObject["board"]!!.jsonPrimitive.content
    val dict = footer["dictionary"]!!.jsonObject["sha256"]!!.jsonPrimitive.content
    return "templates/$board/$dict.json"
}
