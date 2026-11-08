package space.opensensor.model

/**
 * The STAC File `file:checksum` of the stac-opensensor profile, a SHA-256 multihash. That is the
 * multihash code `12` and length `20` (32 bytes) as the prefix `1220`, then the 64 hex characters
 * of the digest. Every reader and writer of the field goes through here, the File Item writer, the
 * Collection parser and the cloud catch up planner, so one rule decides what a checksum is.
 */
object FileChecksum {
    /** The multihash prefix of a SHA-256 digest. */
    const val SHA256_PREFIX: String = "1220"

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** True for 64 lowercase hex characters, the form the archive stores a SHA-256 in. */
    fun isSha256Hex(hex: String): Boolean = SHA256_HEX.matches(hex)

    /** The `file:checksum` of a SHA-256 given as 64 hex characters. Throws on anything else. */
    fun multihash(sha256Hex: String): String {
        val hex = sha256Hex.lowercase()
        require(isSha256Hex(hex)) { "not a SHA-256 hex digest" }
        return SHA256_PREFIX + hex
    }

    /**
     * The lowercase SHA-256 hex of a `file:checksum`, or null when it is not a SHA-256 multihash (another
     * hash, a short digest, a stray character). Hex case is ignored, the digest is the same either way.
     */
    fun sha256Of(checksum: String): String? {
        val text = checksum.lowercase()
        if (!text.startsWith(SHA256_PREFIX)) return null
        return text.removePrefix(SHA256_PREFIX).takeIf(::isSha256Hex)
    }
}
