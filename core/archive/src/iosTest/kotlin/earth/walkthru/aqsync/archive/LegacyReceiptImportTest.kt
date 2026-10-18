@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.protocol.ParquetVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [LegacyReceiptImport] over the files an older iOS build left, into in-memory ports. */
class LegacyReceiptImportTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/receipt-import-test-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val marker = "$base/database/receipts-imported"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Unconfined)
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val crc = ParquetVerify.crc32(bytes)

    private fun name(day: Int) = "station=$station/year=2026/month=09/day=$day/data_0930.parquet"
    private fun final(day: Int) = "$finals/$station/${name(day)}"
    private fun receipt(day: Int) = final(day) + ArchiveLayout.LEGACY_RECEIPT_SUFFIX
    private fun importer() = LegacyReceiptImport(store, marker, Dispatchers.Unconfined)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun writeFile(path: String, content: ByteArray) {
        IosFiles.mkdirs(path.substringBeforeLast('/'))
        IosFiles.writeAtomically(path, content)
    }

    private fun synced(day: Int, receipt: String = """{"size":${bytes.size},"crc":$crc}""") {
        writeFile(final(day), bytes)
        writeFile(receipt(day), receipt.encodeToByteArray())
    }

    @Test
    fun verifiedReceiptsAndPrunedMarksMoveAndTheOldFilesGo() = runTest {
        synced(1)
        synced(2, """{"size":${bytes.size},"crc":$crc,"sha256":"ab","syncedAtEpochMs":7,"sourceDevice":"dev","rows":2,"utcRows":2,"firstUtcNs":1,"lastUtcNs":2,"indexedAtEpochMs":9,"indexLayout":${ArchiveRecord.INDEX_LAYOUT}}""")
        val mark = PrunedFile(station, name(3), 46, 5, 11)
        writeFile("$finals-pruned/pruned.v1", "${PrunedFilesCodec.VERSION}\n$station\t${name(3)}\t46\t5\t11\n".encodeToByteArray())
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()

        assertEquals(LegacyReceiptImport.Result(2, 1), importer().run(index, pruned))

        assertEquals(ArchiveRecord(station, name(1), bytes.size.toLong(), crc, "", 0, ""), index.get(ArchiveKey(station, name(1))))
        val full = index.get(ArchiveKey(station, name(2)))!!
        assertEquals("ab", full.sha256)
        assertEquals(9L, full.indexedAtEpochMs)
        assertEquals(listOf(mark), pruned.observeAll().first())
        assertEquals(IosFiles.Kind.FILE, IosFiles.kind(marker))
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind(receipt(1)))
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind(receipt(2)))
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind("$finals-pruned"))
        assertEquals(listOf(ArchiveKey(station, name(1)), ArchiveKey(station, name(2))), store.listFinal(station).sortedBy { it.name })
    }

    @Test
    fun aHalfWrittenReceiptOrAFileThatFailsTheCheckIsNotSynced() = runTest {
        synced(1, """{"size":${bytes.size},"cr""")
        synced(2, """{"size":${bytes.size},"crc":${crc xor 1}}""")
        synced(3, """{"size":${bytes.size + 1},"crc":$crc}""")
        writeFile(receipt(4), """{"size":${bytes.size},"crc":$crc}""".encodeToByteArray())
        synced(5)
        val index = InMemoryArchiveIndex()

        assertEquals(LegacyReceiptImport.Result(1, 0), importer().run(index, MemoryPruned()))

        assertEquals(listOf(name(5)), index.observeAll().first().map { it.name })
        for (day in 1..5) assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind(receipt(day)))
        assertEquals(IosFiles.Kind.FILE, IosFiles.kind(final(1)))
    }

    @Test
    fun aSecondLaunchImportsNothingAndOnlyDeletesLeftovers() = runTest {
        synced(1)
        importer().run(InMemoryArchiveIndex(), MemoryPruned())
        synced(2)
        writeFile("$finals-pruned/pruned.v1", "${PrunedFilesCodec.VERSION}\n$station\t${name(3)}\t46\t5\t11\n".encodeToByteArray())
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, pruned))

        assertEquals(emptyList(), index.observeAll().first())
        assertEquals(emptyList(), pruned.observeAll().first())
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind(receipt(2)))
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind("$finals-pruned"))
    }

    @Test
    fun aRunCutShortKeepsWhatTheIndexAlreadyHolds() = runTest {
        synced(1)
        val index = InMemoryArchiveIndex()
        val engine = ArchiveRecord(station, name(1), bytes.size.toLong(), crc, "cd", 5, "engine")
        index.put(engine)

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, MemoryPruned()))

        assertEquals(engine, index.get(engine.key))
    }

    @Test
    fun anOlderLayoutReceiptWithoutUtcRowsReadsAsNeverIndexed() {
        val noUtc = """{"size":1,"crc":2,"indexedAtEpochMs":9,"rows":3,"utcRows":0,"purpose":"telemetry"}"""
        val dated = """{"size":1,"crc":2,"indexedAtEpochMs":9,"rows":3,"utcRows":3,"firstUtcNs":1,"lastUtcNs":2}"""
        assertNull(LegacyReceiptImport.decode(station, name(1), noUtc)!!.indexedAtEpochMs)
        assertEquals(9L, LegacyReceiptImport.decode(station, name(1), dated)!!.indexedAtEpochMs)
        assertEquals(9L, LegacyReceiptImport.decode(station, name(1), noUtc.replace("}", ""","indexLayout":${ArchiveRecord.INDEX_LAYOUT}}"""))!!.indexedAtEpochMs)
        assertNull(LegacyReceiptImport.decode(station, name(1), """{"size":-1,"crc":2}"""))
        assertNull(LegacyReceiptImport.decode(station, name(1), """{"size":1,"crc":4294967296}"""))
    }

    private class MemoryPruned : PrunedFiles {
        private val rows = MutableStateFlow<List<PrunedFile>>(emptyList())
        override fun observeAll(): Flow<List<PrunedFile>> = rows
        override suspend fun put(file: PrunedFile) {
            rows.value = rows.value.filterNot { it.key == file.key } + file
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun clear(): Int = rows.value.size.also { rows.value = emptyList() }
    }
}
