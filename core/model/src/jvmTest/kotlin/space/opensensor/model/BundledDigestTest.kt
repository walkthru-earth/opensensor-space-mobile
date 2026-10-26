package space.opensensor.model

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BundledDigestTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun eachBundledTemplateIsKeyedByTheDigestOfItsExactBytes() {
        val templates = BundledFiles.files.filterKeys { it.startsWith("templates/") }
        assertEquals(BundledCatalog.templates.size, templates.size)
        for ((path, text) in templates) {
            val disk = sha256(File("bundled/$path").readBytes())
            assertEquals(disk, sha256(text.encodeToByteArray()), path)
            val template = BundledCatalog.templates.single { it.key.desc == disk }
            assertSame(template, BundledCatalog.templateByDesc(disk))
            assertSame(template, BundledCatalog.template(template.dict, disk))
        }
    }
}
