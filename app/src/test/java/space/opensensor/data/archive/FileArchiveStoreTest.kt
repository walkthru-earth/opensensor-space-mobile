package space.opensensor.data.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveVerifier
import space.opensensor.archive.PartialMeta
import space.opensensor.data.history.info
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.sync.FileArchive
import java.io.File

/** Android's adapters behind the shared ports: same paths as [ArchiveLayout], same partial rules as the in-memory contract store. */
class FileArchiveStoreTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val key = ArchiveKey(thing, "station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val meta = PartialMeta(bytes.size.toLong(), ParquetVerify.crc32(bytes))

    private fun tempDir() = File.createTempFile("aq-store", "").apply { delete(); mkdirs(); deleteOnExit() }

    @Test
    fun `partials and finals land on the shared layout paths`() = runTest {
        val base = tempDir()
        val store = FileArchiveStore(FileArchive(base), Dispatchers.Unconfined)
        store.openPartial(key, meta).use { w ->
            assertEquals(0L, w.resumeFrom)
            w.writeAt(0, bytes.copyOfRange(0, 10))
        }
        assertTrue(File(base, ArchiveLayout.partialPath(key)).isFile)
        assertEquals(ArchiveLayout.encodeMeta(meta), File(base, ArchiveLayout.partialMetaPath(key)).readText())
        assertEquals(mapOf(key.name to 10L), store.partialLengths(thing))
        store.openPartial(key, meta).use { w ->
            assertEquals(10L, w.resumeFrom)
            w.writeAt(10, bytes.copyOfRange(10, bytes.size))
        }
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key, partial = true), meta))
        store.commit(key)
        assertTrue(File(base, ArchiveLayout.finalPath(key)).isFile)
        assertFalse(File(base, ArchiveLayout.partialMetaPath(key)).exists())
        assertTrue(store.exists(key))
        assertEquals(listOf(key), store.listFinal(thing))
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key), meta))
        // Never replaces an archived file.
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes) }
        assertTrue(runCatching { store.commit(key) }.isFailure)
    }

    @Test
    fun `a changed promise restarts the partial`() = runTest {
        val store = FileArchiveStore(FileArchive(tempDir()), Dispatchers.Unconfined)
        store.openPartial(key, meta).use { w -> w.writeAt(0, bytes.copyOfRange(0, 10)) }
        store.openPartial(key, meta.copy(crc32 = meta.crc32 xor 1)).use { w -> assertEquals(0L, w.resumeFrom) }
    }

    @Test
    fun `entity and record carry every column both ways`() {
        val entity = ArchivedFile(
            id = 9, thing = thing, name = key.name, bytes = 1, crc32 = 2, sha256 = "s", syncedAtEpochMs = 3, sourceDevice = "d",
            indexedAtEpochMs = 4, indexError = null, rows = 5, rowGroups = 6, bootId = "b", purpose = "telemetry",
            utcRows = 7, firstUtcNs = 8, lastUtcNs = 9, firstMonoUs = 10, lastMonoUs = 11, firstSeq = 12, lastSeq = 13,
            estFirstUtcNs = 15, estLastUtcNs = 16, estAnchorFile = "a",
        )
        val record: ArchiveRecord = entity.toRecord()
        assertEquals(entity, record.toEntity(id = 9))
        assertEquals(entity.timelineFirstNs, record.timelineFirstNs)
        assertEquals(entity.hasEstimatedUtc, record.hasEstimatedUtc)
        assertEquals(entity.info(), record.info())
    }
}
