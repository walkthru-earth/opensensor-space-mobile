package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileChecksumTest {
    private val sha = "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b"

    @Test
    fun multihashRoundTrips() {
        assertEquals("1220$sha", FileChecksum.multihash(sha))
        assertEquals(sha, FileChecksum.sha256Of(FileChecksum.multihash(sha)))
    }

    @Test
    fun hexCaseNamesTheSameDigest() {
        assertEquals(sha, FileChecksum.sha256Of("1220" + sha.uppercase()))
        assertEquals("1220$sha", FileChecksum.multihash(sha.uppercase()))
    }

    @Test
    fun otherHashesAreNoSha256() {
        assertNull(FileChecksum.sha256Of(sha))
        assertNull(FileChecksum.sha256Of("1114$sha"))
        assertNull(FileChecksum.sha256Of("1220" + sha.dropLast(2)))
        assertNull(FileChecksum.sha256Of("1220${sha}00"))
        assertNull(FileChecksum.sha256Of("1220" + sha.replaceFirst('e', 'g')))
        assertNull(FileChecksum.sha256Of(""))
        assertFailsWith<IllegalArgumentException> { FileChecksum.multihash("abc") }
    }
}
