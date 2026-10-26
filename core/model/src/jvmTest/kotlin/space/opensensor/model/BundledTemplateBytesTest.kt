package space.opensensor.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bundled templates are opensensor-space-firmware `docs/shared/templates` copied byte for byte. A template's desc
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

    /** opensensor-space-firmware 9915aec `docs/shared/templates`. The template pins equal their index desc. */
    private val pinned = mapOf(
        "templates/index.json" to "d51cf30858947e53ff1f583c55d3fcc843dc6dcb787ada7042c01b9acafe8adb",
        "templates/m5stack-cores3/3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9.json" to
            "1c9511bd6836876bf90bbdbe3224b00bd5d176e0fdd5293d5ee6dd73ef6ea45a",
        "templates/waveshare-esp32-s3-sim7670g/50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7.json" to
            "1180e7273bf1dd4cce94ba02b84af61bdddeeb11dc7ddcef61967aca7031c472",
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

    @Test
    fun theBuildDigestOfEveryFileIsTheHashOfItsBytesOnDisk() {
        val onDisk = root.walkTopDown().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes()) }
        assertEquals(onDisk, BundledFiles.sha256)
    }

    @Test
    fun eachTemplateResolvesByItsDescAloneAndWithItsDict() {
        for (desc in expected.values) {
            val template = BundledCatalog.templates.single { it.key.desc == desc }
            assertSame(template, BundledCatalog.templateByDesc(desc))
            assertSame(template, BundledCatalog.template(template.dict, desc))
        }
    }
}
