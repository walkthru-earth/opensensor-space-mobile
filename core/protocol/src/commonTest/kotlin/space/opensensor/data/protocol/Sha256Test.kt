package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** FIPS 180-2 vectors, run on JVM (`MessageDigest`) and on the iOS simulator (CommonCrypto). */
class Sha256Test {
    private fun hex(text: String) = ParquetVerify.sha256Hex(text.encodeToByteArray())

    @Test
    fun emptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
    }

    @Test
    fun oneBlockMessage() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
    }

    @Test
    fun twoBlockMessage() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun oneMillionAStreamedInUnevenSlices() {
        val slice = ByteArray(65_537) { 'a'.code.toByte() }
        val sha = Sha256()
        var left = 1_000_000
        while (left > 0) {
            val n = minOf(left, slice.size)
            sha.update(slice, 0, n)
            left -= n
        }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha.digest().toHex())
    }

    @Test
    fun offsetAndLengthSelectTheSlice() {
        val sha = Sha256()
        sha.update("xxabcyy".encodeToByteArray(), 2, 3)
        sha.update(ByteArray(0), 0, 0)
        assertEquals(hex("abc"), sha.digest().toHex())
    }

    @Test
    fun aFinishedHashRefusesMoreInputAndOutOfRangeSlicesAreRejected() {
        val sha = Sha256()
        assertFailsWith<IndexOutOfBoundsException> { sha.update(ByteArray(4), 2, 3) }
        sha.digest()
        assertFailsWith<IllegalStateException> { sha.update(ByteArray(1), 0, 1) }
        assertFailsWith<IllegalStateException> { sha.digest() }
    }
}
