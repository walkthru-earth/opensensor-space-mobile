package space.opensensor.model

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bundled templates equal opensensor-space-firmware `docs/shared/templates` byte for byte, so the
 * next `dict` or `desc` move fails this gate instead of a phone (#182). The firmware checkout is
 * `OPENSENSOR_FIRMWARE_DIR`, else an `opensensor-space-firmware` folder beside this repository or one
 * of its parents, which also finds it from a worktree. Without one, as on CI, the test is skipped.
 */
class FirmwareTemplatesTest {
    private val bundled = File("bundled/templates")

    private fun firmwareTemplates(): File? {
        System.getenv("OPENSENSOR_FIRMWARE_DIR")?.let { return File(it, TEMPLATES).takeIf(File::isDirectory) }
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "$FIRMWARE/$TEMPLATES") }
            .firstOrNull { File(it, "index.json").isFile }
    }

    private fun jsonFiles(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    @Test
    fun theBundledTemplatesAreTheFirmwareTemplatesByteForByte() {
        val firmware = firmwareTemplates()
        assumeTrue("no $FIRMWARE checkout beside this repository", firmware != null)
        val expected = jsonFiles(checkNotNull(firmware))
        val actual = jsonFiles(bundled)
        assertEquals(expected.keys, actual.keys, "copy $firmware into core/model/bundled/templates, see mobile-migration.md")
        expected.forEach { (path, text) -> assertEquals(text, actual.getValue(path), "$path differs from $firmware") }
    }

    private companion object {
        const val FIRMWARE = "opensensor-space-firmware"
        const val TEMPLATES = "docs/shared/templates"
    }
}
