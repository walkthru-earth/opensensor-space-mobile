package space.opensensor.model

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `bundled/registry` is stac-opensensor `registry/properties.json` and `registry/scales.json` copied
 * byte for byte at e9981360c2229a98d5659ef0038a3b6114953f73 (#16). A reformat, a final newline or a line ending change fails here, and
 * a re-copy updates [pinned] on purpose. Re-copy with
 * `git -C ../stac-opensensor show <commit>:registry/properties.json > core/model/bundled/registry/properties.json`
 * and the same for `scales.json`. Runs on the host, where the files under `core/model/bundled` are readable.
 */
class BundledRegistryBytesTest {
    private val root = File("bundled")

    private val pinned = mapOf(
        "registry/properties.json" to "c687a8237fde0fe509361027d3decaf1e0c3ba5c825c78ff88d764c332e08971",
        "registry/scales.json" to "37a381e5cf3c14ed164e4efbf863dd1614fdc3a92a8229053a8978a29c9a75bd",
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
