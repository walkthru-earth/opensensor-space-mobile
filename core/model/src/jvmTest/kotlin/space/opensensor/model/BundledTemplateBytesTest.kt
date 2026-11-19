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

    /** opensensor-space-firmware a5545b4 `docs/shared/templates`. The template pins equal their index desc. */
    private val pinned = mapOf(
        "templates/index.json" to "e778a890f446b537a84d20449c45a955753370559eede396bac1b138bea3a483",
        "templates/elecrow-crowpanel-epaper-42/e7279c4edbe87071e4a51889124a43d7ffcd1977c79c8575887ae35bdee10903.json" to
            "39da5da001dd662c6c05eddf08bbfdd1400d077dd5a9b7cdf2efaac939d08eb8",
        "templates/m5stack-cores3/f4f81218dc80d1a8e6db043197a2f7180c4f075f9e8348df30eaceef44d68370.json" to
            "38b0982d1da0dfea20777af87544f30c8141ef297ed5146c402129133acbea0e",
        "templates/waveshare-esp32-s3-sim7670g/a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809.json" to
            "f46b72d6f93e1f2077693c32eb03036935d73026d4ee64a149a6c9c347e0af74",
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
