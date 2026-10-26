package space.opensensor.model

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bundled templates are aq-parquet a09f5ae `docs/shared/templates` copied byte for byte. A
 * template's desc is the SHA-256 of its exact bytes, so a reformat, a final newline or a line ending
 * change fails here. Run on the host, where the files under `core/model/bundled` are readable.
 */
class BundledTemplateBytesTest {
    private val expected = mapOf(
        "templates/m5stack-cores3/4f80c26cde8d7ea78c2160225d98386009a8651d9a049b4bf68a75da96015e94.json" to
            "1b7d969abb670579cd1f34f0bd7e224f66b120ed178fbb2fcf024e14a46c3f08",
        "templates/waveshare-esp32-s3-sim7670g/6f567b38fecc165f544626168931b3dc3d674482da87ed78cb0bec5bf3dc6ceb.json" to
            "e6b32af089b5c0b8389999a09b4a81ffe36d15f908a2002765025f78853b7d32",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun everyBundledTemplateFileHashesToItsDesc() {
        val root = File("bundled")
        val onDisk = root.resolve("templates").walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name != "index.json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes()) }
        assertEquals(expected, onDisk)
    }

    @Test
    fun theEmbeddedTemplateTextHashesToItsDesc() {
        expected.forEach { (path, desc) -> assertEquals(desc, sha256(BundledFiles.files.getValue(path).encodeToByteArray()), path) }
    }

    @Test
    fun everyBundledTemplateCarriesTheDescOfItsBytes() {
        assertEquals(expected.values.toSet(), BundledCatalog.templates.map { it.key.desc }.toSet())
    }
}
