@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import space.opensensor.data.protocol.ParquetVerify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [LegacyReceiptImport] over the files an older iOS build left, into in-memory ports. */
class LegacyReceiptImportTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/receipt-import-test-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val marker = "$base/database/receipts-imported"
    private val settled = "$base/database/receipts-settled"
    private val prunedFile = "$finals-pruned/pruned.v1"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Unconfined)
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val crc = ParquetVerify.crc32(bytes)

    private fun name(day: Int) = "thing=$thing/year=2026/month=09/day=$day/data_0930.parquet"
    private fun final(day: Int) = "$finals/$thing/${name(day)}"
    private fun receipt(day: Int) = final(day) + ArchiveLayout.LEGACY_RECEIPT_SUFFIX
    private fun importer() = LegacyReceiptImport(store, marker, settled, Dispatchers.Unconfined)
    private fun kind(path: String) = IosFiles.kind(path)

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

    private fun writePruned(vararg days: Int) {
        val lines = days.joinToString("") { "$thing\t${name(it)}\t46\t5\t11\n" }
        writeFile(prunedFile, "${PrunedFilesCodec.VERSION}\n$lines".encodeToByteArray())
    }

    private fun mark(day: Int) = PrunedFile(thing, name(day), 46, 5, 11)

    /** A later pass must not checkpoint, so a busy database cannot break startup. */
    private val noCheckpoint: suspend () -> Unit = { error("checkpoint after the first pass") }

    private fun legacyFiles(): List<String> = IosFiles.walk(finals).filter { it.endsWith(ArchiveLayout.LEGACY_RECEIPT_SUFFIX) } +
        listOf("$finals-pruned").filter { kind(it) != IosFiles.Kind.ABSENT }

    @Test
    fun theFirstLaunchCopiesALaterLaunchDeletesAndThenNothingRuns() = runTest {
        synced(1)
        synced(2, """{"size":${bytes.size},"crc":$crc,"sha256":"ab","syncedAtEpochMs":7,"sourceDevice":"dev","rows":2,"utcRows":2,"firstUtcNs":1,"lastUtcNs":2,"indexedAtEpochMs":9}""")
        writePruned(3)
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()
        val markerAtDurable = mutableListOf<IosFiles.Kind>()

        assertEquals(LegacyReceiptImport.Result(2, 1), importer().run(index, pruned) { markerAtDurable += kind(marker) })

        assertEquals(listOf(IosFiles.Kind.ABSENT), markerAtDurable)
        assertEquals(ArchiveRecord(thing, name(1), bytes.size.toLong(), crc, "", 0, ""), index.get(ArchiveKey(thing, name(1))))
        val full = index.get(ArchiveKey(thing, name(2)))!!
        assertEquals("ab", full.sha256)
        assertEquals(2L, full.utcRows)
        assertNull(full.indexedAtEpochMs)
        assertEquals(listOf(mark(3)), pruned.observeAll().first())
        assertEquals(IosFiles.Kind.FILE, kind(marker))
        // Nothing goes before a later launch has seen the rows.
        assertEquals(IosFiles.Kind.FILE, kind(receipt(1)))
        assertEquals(IosFiles.Kind.FILE, kind(prunedFile))

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, pruned, noCheckpoint))

        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
        assertEquals(listOf(ArchiveKey(thing, name(1)), ArchiveKey(thing, name(2))), store.listFinal(thing).sortedBy { it.name })
        assertEquals(2, index.observeAll().first().size)
        assertEquals(listOf(mark(3)), pruned.observeAll().first())

        // Settled, so a later launch neither walks the archive nor checkpoints.
        synced(4)
        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, pruned, noCheckpoint))
        assertEquals(IosFiles.Kind.FILE, kind(receipt(4)))
        assertNull(index.get(ArchiveKey(thing, name(4))))
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

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, MemoryPruned(), noCheckpoint))
        assertEquals(listOf(name(5)), index.observeAll().first().map { it.name })
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(final(1)))
    }

    @Test
    fun aReceiptThatThrowsIsSkippedAndTheGoodOnesStillMove() = runTest {
        synced(1)
        writeFile("$base/elsewhere", bytes)
        IosFiles.mkdirs(final(2).substringBeforeLast('/'))
        NSFileManager.defaultManager.createSymbolicLinkAtPath(final(2), "$base/elsewhere", null)
        writeFile(receipt(2), """{"size":${bytes.size},"crc":$crc}""".encodeToByteArray())
        synced(3)
        writePruned(4)
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()

        assertEquals(LegacyReceiptImport.Result(2, 1), importer().run(index, pruned))
        assertEquals(listOf(name(1), name(3)), index.observeAll().first().map { it.name }.sorted())
        assertEquals(listOf(mark(4)), pruned.observeAll().first())

        importer().run(index, pruned, noCheckpoint)
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
    }

    @Test
    fun aPutThatThrowsLeavesEveryOldFileAndALaterRunFinishes() = runTest {
        synced(1)
        synced(2)
        writePruned(3)
        val index = InMemoryArchiveIndex()
        val failing = object : ArchiveIndex by index {
            var puts = 0
            override suspend fun putIfAbsent(record: ArchiveRecord): Boolean {
                if (++puts == 2) error("disk full")
                return index.putIfAbsent(record)
            }
        }
        val pruned = MemoryPruned()

        assertFailsWith<IllegalStateException> { importer().run(failing, pruned) }

        assertEquals(IosFiles.Kind.FILE, kind(receipt(1)))
        assertEquals(IosFiles.Kind.FILE, kind(receipt(2)))
        assertEquals(IosFiles.Kind.FILE, kind(prunedFile))
        assertEquals(IosFiles.Kind.ABSENT, kind(marker))

        assertEquals(LegacyReceiptImport.Result(1, 1), importer().run(index, pruned))
        assertEquals(2, index.observeAll().first().size)
        assertEquals(listOf(mark(3)), pruned.observeAll().first())
    }

    @Test
    fun aRowOrMarkRemovedBetweenLaunchesStaysRemoved() = runTest {
        synced(1)
        synced(2)
        writePruned(3, 4)
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()
        importer().run(index, pruned)
        // Removed on purpose before the next launch, as when the device stops listing a file or a delete is undone.
        index.remove(ArchiveKey(thing, name(1)))
        pruned.remove(mark(3).key)

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, pruned, noCheckpoint))

        assertEquals(listOf(name(2)), index.observeAll().first().map { it.name })
        assertEquals(listOf(mark(4)), pruned.observeAll().first())
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
    }

    @Test
    fun downloadAgainBetweenLaunchesStaysEmpty() = runTest {
        writePruned(1, 2)
        val pruned = MemoryPruned()
        importer().run(InMemoryArchiveIndex(), pruned)

        assertEquals(2, pruned.clear())
        importer().run(InMemoryArchiveIndex(), pruned, noCheckpoint)

        assertEquals(emptyList(), pruned.observeAll().first())
        assertEquals(emptyList(), legacyFiles())
    }

    @Test
    fun aLaunchCutShortInTheDeletesFinishesThemWithoutImporting() = runTest {
        synced(1)
        synced(2)
        writePruned(3)
        val index = InMemoryArchiveIndex()
        val pruned = MemoryPruned()
        importer().run(index, pruned)
        // The app died after deleting receipt 1, and the rows went since.
        NSFileManager.defaultManager.removeItemAtPath(receipt(1), null)
        index.remove(ArchiveKey(thing, name(2)))
        pruned.remove(mark(3).key)

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, pruned, noCheckpoint))

        assertEquals(listOf(name(1)), index.observeAll().first().map { it.name })
        assertEquals(emptyList(), pruned.observeAll().first())
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
    }

    @Test
    fun aDeleteThatFailsLeavesTheImportUnsettledForALaterLaunch() = runTest {
        synced(1)
        val index = InMemoryArchiveIndex()
        importer().run(index, MemoryPruned())
        val dir = receipt(1).substringBeforeLast('/')
        fun mode(bits: Int) = check(NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFilePosixPermissions to bits), dir, null))

        mode(0b101_101_101)
        try {
            importer().run(index, MemoryPruned(), noCheckpoint)
            assertEquals(IosFiles.Kind.FILE, kind(receipt(1)))
            assertEquals(IosFiles.Kind.ABSENT, kind(settled))
        } finally {
            mode(0b111_101_101)
        }

        importer().run(index, MemoryPruned(), noCheckpoint)
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
    }

    @Test
    fun anArchiveThatCannotBeListedLeavesEachPassForALaterLaunch() = runTest {
        synced(1)
        val index = InMemoryArchiveIndex()
        fun mode(bits: Int) = check(NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFilePosixPermissions to bits), finals, null))

        mode(0)
        try {
            assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, MemoryPruned()))
            assertEquals(IosFiles.Kind.ABSENT, kind(marker))
        } finally {
            mode(0b111_101_101)
        }
        assertEquals(LegacyReceiptImport.Result(1, 0), importer().run(index, MemoryPruned()))

        mode(0)
        try {
            importer().run(index, MemoryPruned(), noCheckpoint)
            assertEquals(IosFiles.Kind.ABSENT, kind(settled))
        } finally {
            mode(0b111_101_101)
        }
        assertEquals(IosFiles.Kind.FILE, kind(receipt(1)))

        importer().run(index, MemoryPruned(), noCheckpoint)
        assertEquals(emptyList(), legacyFiles())
        assertEquals(IosFiles.Kind.FILE, kind(settled))
    }

    @Test
    fun aRunCutShortKeepsWhatTheIndexAlreadyHolds() = runTest {
        synced(1)
        val index = InMemoryArchiveIndex()
        val engine = ArchiveRecord(thing, name(1), bytes.size.toLong(), crc, "cd", 5, "engine")
        index.put(engine)

        assertEquals(LegacyReceiptImport.Result(0, 0), importer().run(index, MemoryPruned()))

        assertEquals(engine, index.get(engine.key))
    }

    @Test
    fun everyReceiptReadsAsNeverIndexedSoRepairReadsItsFooterDigests() {
        val noUtc = """{"size":1,"crc":2,"indexedAtEpochMs":9,"rows":3,"utcRows":0,"purpose":"telemetry"}"""
        val dated = """{"size":1,"crc":2,"indexedAtEpochMs":9,"rows":3,"utcRows":3,"firstUtcNs":1,"lastUtcNs":2}"""
        assertNull(LegacyReceiptImport.decode(thing, name(1), noUtc)!!.indexedAtEpochMs)
        assertNull(LegacyReceiptImport.decode(thing, name(1), dated)!!.indexedAtEpochMs)
        assertNull(LegacyReceiptImport.decode(thing, name(1), """{"size":-1,"crc":2}"""))
        assertNull(LegacyReceiptImport.decode(thing, name(1), """{"size":1,"crc":4294967296}"""))
    }

    private class MemoryPruned : PrunedFiles {
        private val rows = MutableStateFlow<List<PrunedFile>>(emptyList())
        override fun observeAll(): Flow<List<PrunedFile>> = rows
        override suspend fun forThing(thing: String): List<PrunedFile> = rows.value.filter { it.thing == thing }
        override suspend fun put(file: PrunedFile) {
            rows.value = rows.value.filterNot { it.key == file.key } + file
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun clear(): Int = rows.value.size.also { rows.value = emptyList() }
    }
}
