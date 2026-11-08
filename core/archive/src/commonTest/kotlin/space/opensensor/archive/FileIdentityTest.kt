package space.opensensor.archive

import kotlinx.coroutines.test.runTest
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

    @Test
    fun onlyBoardFilesAreMatchedByTheirItemId() {
        assertTrue(FileIdentity.isBoardFile(name))
        assertTrue(FileIdentity.isBoardFile(name.substringAfterLast('/')))
        assertFalse(FileIdentity.isBoardFile("thing=$thing/year=2026/month=09/day=15/day.parquet"))
        assertFalse(FileIdentity.isBoardFile("thing=$thing/year=2026/month=09/day=15/data_1000.parquet"))
    }

    @Test
    fun theDefaultSameFileNeedsTheSameSha256() = runTest {
        val index = InMemoryArchiveIndex()
        val record = ArchiveRecord(thing, name, 1000, crc32 = 1, sha256 = sha, syncedAtEpochMs = 1, sourceDevice = "ble")
        index.put(record)
        val identity = FileIdentity.of(thing, name, sha)!!
        assertEquals(record, index.sameFile(identity))
        val otherBytes = identity.copy(sha256 = "cd".repeat(32))
        assertNull(index.sameFile(otherBytes))
        assertFalse(index.markUploaded(otherBytes, atEpochMs = 9))
        assertNull(index.get(record.key)?.uploadedAtEpochMs)
        assertTrue(index.markUploaded(identity, atEpochMs = 9))
        assertEquals(9L, index.get(record.key)?.uploadedAtEpochMs)
    }
}
