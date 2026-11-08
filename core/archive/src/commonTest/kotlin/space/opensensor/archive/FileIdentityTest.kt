package space.opensensor.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIdentityTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val name = "thing=$thing/year=2026/month=09/day=15/data_1000_b41d37a48c04031e9397e28ce48c2f43_0-59-1.parquet"
    private val sha = "ab".repeat(32)

    @Test
    fun theItemIdIsTheFileNameWithoutFoldersOrSuffix() {
        assertEquals("data_1000_b41d37a48c04031e9397e28ce48c2f43_0-59-1", FileIdentity.itemIdOf(name))
        assertEquals("data_1000", FileIdentity.itemIdOf("data_1000.parquet"))
    }

    @Test
    fun theChecksumIsTheSha256Multihash() {
        assertEquals("1220$sha", FileIdentity.of(thing, name, sha)?.checksum)
    }

    @Test
    fun aRecordWithoutSha256HasNoIdentity() {
        assertNull(FileIdentity.of(thing, name, ""))
        assertNull(FileIdentity.of(thing, name, sha.uppercase()))
        assertNull(FileIdentity.of("", name, sha))
        assertFailsWith<IllegalArgumentException> { FileIdentity(thing, "a/b", sha) }
    }

    @Test
    fun theSameBytesUnderOneNameAreOneFileWhateverTheFolder() {
        val record = ArchiveRecord(thing, name, 10, 1, sha, syncedAtEpochMs = 1, sourceDevice = "ble")
        val cloudName = "data_1000_b41d37a48c04031e9397e28ce48c2f43_0-59-1.parquet"
        assertEquals(FileIdentity.of(thing, cloudName, sha), record.identity)
        assertFalse(record.isUploaded)
        assertTrue(record.copy(uploadedAtEpochMs = 5).isUploaded)
    }
}
