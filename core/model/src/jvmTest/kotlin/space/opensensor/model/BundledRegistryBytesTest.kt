package space.opensensor.model

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `bundled/registry` is stac-opensensor `registry/properties.json` and `registry/scales.json` copied
 * byte for byte at 204365f929e6f77bcaa6e70a0245c96cadc5c115, main after #27. A reformat, a final newline or a line ending change fails here, and
 * a re-copy updates [pinned] on purpose. Re-copy with
 * `git -C ../stac-opensensor show <commit>:registry/properties.json > core/model/bundled/registry/properties.json`
 * and the same for `scales.json`. Runs on the host, where the files under `core/model/bundled` are readable.
 */
class BundledRegistryBytesTest {
    private val root = File("bundled")

    private val pinned = mapOf(
        "registry/properties.json" to "16458c74dbdafb3b18de72d7f474a9f49790fb299d1429cea7057b5c5c6d1b52",
        "registry/scales.json" to "1b0bd3aa8ddf81dfa1d44068c3e133fb9b9edb145da1dae6e100b62f2aa6cc3e",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun everyRegistryFileOnDiskMatchesItsPinnedHash() {
        val onDisk = root.resolve("registry").walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes()) }
        assertEquals(pinned, onDisk)
    }

    @Test
    fun theEmbeddedRegistryTextHashesToItsPin() {
        pinned.forEach { (path, hash) ->
            assertEquals(hash, sha256(BundledFiles.files.getValue(path).encodeToByteArray()), path)
            assertEquals(hash, BundledFiles.sha256.getValue(path), path)
        }
    }
}
