package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.Crc32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A sink that keeps the bytes in memory. */
class MemorySink : ExportSink {
    private val out = ArrayList<Byte>()
    var closed = false
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) out += bytes[i]
    }
    override fun close() {
        closed = true
    }
    fun bytes(): ByteArray = out.toByteArray()
}

/** A minimal ZIP reader for the tests, following the end records like a real reader does. */
class ParsedZip(private val b: ByteArray) {
    class Entry(val name: String, val crc: Long, val size: Long, val offset: Long, val time: Int, val date: Int, val flags: Int, val method: Int)

    val entries: List<Entry>
    val usedZip64: Boolean

    init {
        var eocd = b.size - 22
        while (eocd >= 0 && u32(eocd) != 0x06054b50L) eocd--
        check(eocd >= 0) { "no end record" }
        var count = u16(eocd + 10).toLong()
        var cdSize = u32(eocd + 12)
        var cdStart = u32(eocd + 16)
        usedZip64 = count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdStart == 0xFFFFFFFFL
        if (usedZip64) {
            val locator = eocd - 20
            check(u32(locator) == 0x07064b50L)
            val e64 = u64(locator + 8).toInt()
            check(u32(e64) == 0x06064b50L)
            count = u64(e64 + 32)
            cdSize = u64(e64 + 40)
            cdStart = u64(e64 + 48)
        }
        val list = ArrayList<Entry>()
        var at = cdStart.toInt()
        repeat(count.toInt()) {
            check(u32(at) == 0x02014b50L)
            val flags = u16(at + 8)
            val method = u16(at + 10)
            val time = u16(at + 12)
            val date = u16(at + 14)
            val crc = u32(at + 16)
            var size = u32(at + 24)
            val nameLen = u16(at + 28)
            val extraLen = u16(at + 30)
            val commentLen = u16(at + 32)
            var offset = u32(at + 42)
            val name = b.copyOfRange(at + 46, at + 46 + nameLen).decodeToString()
            var x = at + 46 + nameLen
            val end = x + extraLen
            while (x < end) {
                val id = u16(x)
                val len = u16(x + 2)
                if (id == 1) {
                    var p = x + 4
                    if (size == 0xFFFFFFFFL) {
                        size = u64(p)
                        p += 16
                    }
                    if (offset == 0xFFFFFFFFL) offset = u64(p)
                }
                x += 4 + len
            }
            list += Entry(name, crc, size, offset, time, date, flags, method)
            at = end + commentLen
        }
        check(at.toLong() == cdStart + cdSize)
        entries = list
    }

    /** The stored bytes of [e], read through its local header. */
    fun data(e: Entry): ByteArray {
        val h = e.offset.toInt()
        check(u32(h) == 0x04034b50L)
        assertEquals(e.crc, u32(h + 14))
        val start = h + 30 + u16(h + 26) + u16(h + 28)
        return b.copyOfRange(start, start + e.size.toInt())
    }

    private fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
    private fun u64(i: Int) = u32(i) or (u32(i + 4) shl 32)
}

class StoredZipWriterTest {
    private fun crc(bytes: ByteArray) = Crc32().apply { update(bytes, 0, bytes.size) }.value

    private fun zip(zip64Limit: Long = 0xFFFFFFFFL, entryLimit: Int = 0xFFFF, block: StoredZipWriter.() -> Unit): ByteArray {
        val sink = MemorySink()
        StoredZipWriter(sink, zip64Limit, entryLimit).apply {
            block()
            finish()
        }
        return sink.bytes()
    }

    @Test
    fun entriesRoundTripWithNamesCrcAndBytes() {
        val a = ByteArray(200_000) { (it * 31).toByte() }
        val b = "PAR1 small".encodeToByteArray()
        val bytes = zip {
            add("ST1/a.parquet", ByteArrayReader(a), 0)
            add("ST2/ب.parquet", ByteArrayReader(b), 0)
            add("ST2/empty.parquet", ByteArrayReader(ByteArray(0)), 0)
        }
        val parsed = ParsedZip(bytes)
        assertEquals(listOf("ST1/a.parquet", "ST2/ب.parquet", "ST2/empty.parquet"), parsed.entries.map { it.name })
        assertTrue(parsed.entries.all { it.method == 0 && it.flags and (1 shl 11) != 0 })
        assertEquals(false, parsed.usedZip64)
        assertContentEquals(a, parsed.data(parsed.entries[0]))
        assertContentEquals(b, parsed.data(parsed.entries[1]))
        assertEquals(crc(a), parsed.entries[0].crc)
        assertEquals(0, parsed.data(parsed.entries[2]).size)
    }

    @Test
    fun anEmptyZipIsJustTheEndRecord() {
        val bytes = zip { }
        assertEquals(22, bytes.size)
        assertEquals(0, ParsedZip(bytes).entries.size)
    }

    @Test
    fun largeEntriesAndOffsetsUseZip64() {
        val a = ByteArray(300) { it.toByte() }
        val b = ByteArray(50) { 7 }
        val bytes = zip(zip64Limit = 100) {
            add("a.parquet", ByteArrayReader(a), 0)
            add("b.parquet", ByteArrayReader(b), 0)
        }
        val parsed = ParsedZip(bytes)
        assertTrue(parsed.usedZip64)
        assertContentEquals(a, parsed.data(parsed.entries[0]))
        assertContentEquals(b, parsed.data(parsed.entries[1]))
        assertTrue(parsed.entries[1].offset >= 100)
    }

    @Test
    fun manyEntriesUseZip64EndRecords() {
        val bytes = zip(entryLimit = 2) {
            for (i in 0 until 3) add("f$i.parquet", ByteArrayReader(byteArrayOf(i.toByte())), 0)
        }
        val parsed = ParsedZip(bytes)
        assertTrue(parsed.usedZip64)
        assertEquals(3, parsed.entries.size)
        assertContentEquals(byteArrayOf(2), parsed.data(parsed.entries[2]))
    }

    @Test
    fun unsafeOrRepeatedNamesAreRefused() {
        val w = StoredZipWriter(MemorySink())
        val r = ByteArrayReader(byteArrayOf(1))
        for (bad in listOf("", "/abs", "a/../b", "a//b", "a/")) {
            assertFailsWith<IllegalArgumentException>(bad) { w.add(bad, r, 0) }
        }
        w.add("a", r, 0)
        assertFailsWith<IllegalArgumentException> { w.add("a", r, 0) }
    }

    @Test
    fun bytesThatChangeDuringTheCopyFailTheExport() {
        var reads = 0
        val shifting = object : ByteReader {
            override val size = 4L
            override fun read(offset: Long, length: Int): ByteArray = ByteArray(length) { (reads + it).toByte() }.also { reads++ }
        }
        assertFailsWith<ExportChangedException> { StoredZipWriter(MemorySink()).add("x.parquet", shifting, 0) }
    }

    @Test
    fun dosTimeIsUtcAndClamped() {
        // 2024-03-05 14:07:09 UTC.
        val (time, date) = StoredZipWriter.dosTime(1_709_647_629_000)
        assertEquals((14 shl 11) or (7 shl 5) or 4, time)
        assertEquals(((2024 - 1980) shl 9) or (3 shl 5) or 5, date)
        assertEquals(0 to ((1 shl 5) or 1), StoredZipWriter.dosTime(0))
    }
}

/** An [ExportSpace] in memory that records which runs were created and deleted. */
class MemoryExportSpace : ExportSpace {
    inner class Run(val id: Int) : ExportRun {
        val files = LinkedHashMap<String, MemorySink>()
        var deleted = false
        override fun path(name: String) = "mem/$id/$name"
        override fun create(name: String): ExportSink {
            require(name !in files) { "exists" }
            return MemorySink().also { files[name] = it }
        }
        override fun delete() {
            deleted = true
        }
    }

    val runs = ArrayList<Run>()

    override fun newRun(): ExportRun {
        runs.forEach { it.deleted = true }
        return Run(runs.size).also { runs += it }
    }
}

class ArchiveExportTest {
    private fun entry(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) = ExportEntry(path, 0) { ByteArrayReader(bytes) }

    @Test
    fun zipNamesUseUtcStamps() {
        assertEquals("opensensor-space-archive-20240305-140709.zip", ArchiveExport.archiveZipName(1_709_647_629_000))
        assertEquals("opensensor-space-daily-20240305-140709.zip", ArchiveExport.dailyZipName(1_709_647_629_000))
    }

    @Test
    fun dailyEntriesNeedAParquetFile() {
        assertEquals(null, ArchiveExport.dailyEntries(listOf(entry("derived/v1/x.manifest.json"))))
        val kept = ArchiveExport.dailyEntries(
            listOf(entry("derived/v1/b.parquet"), entry("derived/v1/tmp.part"), entry("derived/v1/a.manifest.json")),
        )
        assertEquals(listOf("derived/v1/a.manifest.json", "derived/v1/b.parquet"), kept?.map { it.path })
    }

    @Test
    fun copiesOfTheSameNameFromTwoStationsStayApart() {
        val space = MemoryExportSpace()
        val staged = ArchiveExport.stageCopies(space, listOf(entry("A/x.parquet"), entry("B/x.parquet"), entry("A/y.parquet")))
        assertEquals(listOf("A-x.parquet", "B-x.parquet", "y.parquet"), staged.map { it.name })
        assertTrue(staged.all { it.mime == ArchiveExport.PARQUET_MIME })
        val run = space.runs.single()
        assertContentEquals(byteArrayOf(1, 2, 3), run.files.getValue("y.parquet").bytes())
        assertTrue(run.files.values.all { it.closed })
    }

    @Test
    fun aZipIsStagedInAFreshRunAndTheOldRunIsSwept() {
        val space = MemoryExportSpace()
        ArchiveExport.stageZip(space, listOf(entry("A/x.parquet")), "one.zip")
        val staged = ArchiveExport.stageZip(space, listOf(entry("A/x.parquet"), entry("B/x.parquet")), "two.zip")
        assertEquals("mem/1/two.zip", staged.path)
        assertTrue(space.runs[0].deleted)
        val parsed = ParsedZip(space.runs[1].files.getValue("two.zip").bytes())
        assertEquals(listOf("A/x.parquet", "B/x.parquet"), parsed.entries.map { it.name })
    }

    @Test
    fun aFailedExportRemovesItsRun() {
        val space = MemoryExportSpace()
        val broken = ExportEntry("A/x.parquet", 0) { error("gone") }
        assertFailsWith<IllegalStateException> { ArchiveExport.stageZip(space, listOf(broken), "z.zip") }
        assertTrue(space.runs.single().deleted)
        assertFailsWith<IllegalStateException> { ArchiveExport.stageCopies(space, listOf(broken)) }
        assertTrue(space.runs.last().deleted)
    }

    @Test
    fun archiveEntriesListEveryFinalFileSorted() = kotlinx.coroutines.test.runTest {
        val store = InMemoryArchiveStore()
        store.files["${ArchiveLayout.ARCHIVE}/B/b.parquet"] = byteArrayOf(9)
        store.files["${ArchiveLayout.ARCHIVE}/A/a.parquet"] = byteArrayOf(8)
        val entries = ArchiveExport.archiveEntries(store, emptyList(), nowEpochMs = 42)
        assertEquals(listOf("A/a.parquet", "B/b.parquet"), entries.map { it.path })
        assertEquals(42, entries.first().modifiedEpochMs)
        assertContentEquals(byteArrayOf(8), entries.first().open().read(0, 1))
    }
}
