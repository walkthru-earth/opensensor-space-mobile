package earth.walkthru.aqsync.data.protocol

/**
 * Streaming SHA-256, the fingerprint recorded with every archived file. It is not part of the
 * completion rule ([ParquetVerify] decides that with size, CRC-32 and `PAR1`), it pins the bytes
 * that were verified so a later copy can be compared.
 *
 * The JVM actual uses `java.security.MessageDigest` and iOS uses CommonCrypto. Both are checked
 * against the FIPS 180-2 vectors in `commonTest`.
 */
expect class Sha256() {
    /** Adds `bytes[offset until offset + length]` to the running hash. */
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** Finishes the hash and returns its 32 bytes. The instance cannot be used afterwards. */
    fun digest(): ByteArray
}

/** SHA-256 of [bytes] as 64 lowercase hex characters. */
fun ParquetVerify.sha256Hex(bytes: ByteArray): String = Sha256().apply { update(bytes, 0, bytes.size) }.digest().toHex()
