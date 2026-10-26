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
 * folder needs no edit. Runs on the host, where the files under `core/model/bundled` are readable.
 */
class BundledTemplateBytesTest {
    private val root = File("bundled")

    /** `templates/<href>` to desc, from the `templates` map of the bundled `index.json`. */
    private val expected: Map<String, String> =
        Json.parseToJsonElement(root.resolve("templates/index.json").readText()).jsonObject.getValue("templates").jsonObject.values.associate { entry ->
            val e = entry.jsonObject
            "templates/" + e.getValue("href").jsonPrimitive.content to e.getValue("desc").jsonPrimitive.content
        }

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
    fun theEmbeddedTemplateTextHashesToItsIndexDesc() {
        expected.forEach { (path, desc) -> assertEquals(desc, sha256(BundledFiles.files.getValue(path).encodeToByteArray()), path) }
    }

    @Test
    fun everyBundledTemplateCarriesTheDescOfItsBytes() {
        assertEquals(expected.values.toSet(), BundledCatalog.templates.map { it.key.desc }.toSet())
    }
}
