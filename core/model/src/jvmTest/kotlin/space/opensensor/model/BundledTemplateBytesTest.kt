package space.opensensor.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled templates are aq-parquet `docs/shared/templates` copied byte for byte. A template's desc
 * is the SHA-256 of its exact bytes and `index.json` names it, so a reformat, a final newline or a line
 * ending change fails here. The expected hashes come from the copied `index.json`, so a re-copy of the
 * folder needs no edit there. [pinned] holds the full SHA-256 of each copied file, `index.json`
 * included, so a re-copy also updates [pinned] on purpose. Runs on the host, where the files under `core/model/bundled` are readable.
 */
class BundledTemplateBytesTest {
    private val root = File("bundled")

    /** `templates/<href>` to desc, from the `templates` map of the bundled `index.json`. */
    private val expected: Map<String, String> =
        Json.parseToJsonElement(root.resolve("templates/index.json").readText()).jsonObject.getValue("templates").jsonObject.values.associate { entry ->
            val e = entry.jsonObject
            "templates/" + e.getValue("href").jsonPrimitive.content to e.getValue("desc").jsonPrimitive.content
        }

    /** aq-parquet 248c487 `docs/shared/templates`. The template pins equal their index desc. */
    private val pinned = mapOf(
        "templates/index.json" to "e45494923e634eba111bb98a66aab60c2ec9ab5c6c4f8770b428fd8a37437f0f",
        "templates/m5stack-cores3/4f80c26cde8d7ea78c2160225d98386009a8651d9a049b4bf68a75da96015e94.json" to
            "bb0ab0a3eaafb05e45bbbf5216194ab808110df92e6ee277c8ef2af6f2f314b0",
        "templates/waveshare-esp32-s3-sim7670g/6f567b38fecc165f544626168931b3dc3d674482da87ed78cb0bec5bf3dc6ceb.json" to
            "7f2bbf5314ce8d6f4d0e5ee518991a9c3d43ab7b66d973e8a5921e811059872a",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun everyBundledTemplateFileHashesToItsIndexDesc() {
        assertTrue(expected.isNotEmpty())
        expected.values.forEach { assertTrue(Regex("^[0-9a-f]{64}$").matches(it), it) }
        val onDisk = root.resolve("templates").walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name != "index.json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes()) }
        assertEquals(expected, onDisk)
    }

    @Test
    fun everyCopiedJsonFileMatchesItsPinnedHash() {
        val onDisk = root.resolve("templates").walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes()) }
        assertEquals(pinned, onDisk)
        pinned.forEach { (path, hash) -> assertEquals(hash, sha256(BundledFiles.files.getValue(path).encodeToByteArray()), path) }
        assertEquals(expected, pinned - "templates/index.json")
    }

    @Test
    fun theEmbeddedTemplateTextHashesToItsIndexDesc() {
        expected.forEach { (path, desc) -> assertEquals(desc, sha256(BundledFiles.files.getValue(path).encodeToByteArray()), path) }
    }

    @Test
    fun everyBundledTemplateCarriesTheDescOfItsBytes() {
        assertEquals(expected.values.toSet(), BundledCatalog.templates.map { it.key.desc }.toSet())
    }
}
