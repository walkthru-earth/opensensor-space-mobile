package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.Crc32
import earth.walkthru.aqsync.storage.StorageException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        for (bad in listOf("", "/abs", "a/../b", "a//b", "a/", "./a", "a\\..\\b", "C:/x", "a\u0000b", "a\nb")) {
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

/** An [ExportSpace] in memory that records which runs were created, released and deleted. */
class MemoryExportSpace : ExportSpace {
    inner class Run(val id: Int) : ExportRun {
        val files = LinkedHashMap<String, MemorySink>()
        var deleted = false
        var released = false
        override fun path(name: String) = "mem/$id/$name"
        override fun create(name: String): ExportSink {
            require(name !in files) { "exists" }
            return MemorySink().also { files[name] = it }
        }
        override fun release() {
            released = true
        }
        override fun delete() {
            deleted = true
        }
    }

    val runs = ArrayList<Run>()

    /** What [freeBytes] reports, null when unknown. */
    var free: Long? = null

    override fun freeBytes(): Long? = free

    override fun newRun(): ExportRun = Run(runs.size).also { runs += it }
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
        assertEquals(null, ArchiveExport.dailyEntries(listOf(entry("derived/v1/A/x.manifest.json")), setOf("A")))
        val kept = ArchiveExport.dailyEntries(
            listOf(entry("derived/v1/A/b.parquet"), entry("derived/v1/A/tmp.part"), entry("derived/v1/A/a.manifest.json")),
            setOf("A"),
        )
        assertEquals(listOf("derived/v1/A/a.manifest.json", "derived/v1/A/b.parquet"), kept?.map { it.path })
    }

    @Test
    fun dailyEntriesKeepOnlyTheGivenStations() {
        val derived = listOf(
            entry("derived/v1/A/day=2026-10-01.parquet"),
            entry("derived/v1/A/day=2026-10-01.manifest.json"),
            entry("derived/v1/B/day=2026-10-01.parquet"),
            entry("derived/v1/B/day=2026-10-01.manifest.json"),
            // Outside any station directory.
            entry("derived/v1/loose.parquet"),
        )
        assertEquals(
            listOf("derived/v1/A/day=2026-10-01.manifest.json", "derived/v1/A/day=2026-10-01.parquet"),
            ArchiveExport.dailyEntries(derived, setOf("A"))?.map { it.path },
        )
        assertEquals(4, ArchiveExport.dailyEntries(derived, setOf("A", "B"))?.size)
        // Only another station's day files is nothing to share.
        assertEquals(null, ArchiveExport.dailyEntries(derived, setOf("C")))
    }

    @Test
    fun aFileRightUnderDerivedBelongsToNoStation() {
        // Its own name is no station folder, even when a station has that name.
        val loose = listOf(entry("derived/v1/loose.parquet"), entry("derived/v1/loose.manifest.json"))
        assertEquals(null, ArchiveExport.dailyEntries(loose, setOf("loose.parquet", "loose.manifest.json")))
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
    fun aZipIsStagedInAFreshRunAndTheOldRunStays() {
        val space = MemoryExportSpace()
        ArchiveExport.stageZip(space, listOf(entry("A/x.parquet")), "one.zip")
        val staged = ArchiveExport.stageZip(space, listOf(entry("A/x.parquet"), entry("B/x.parquet")), "two.zip")
        assertEquals("mem/1/two.zip", staged.path)
        assertFalse(space.runs[0].deleted)
        assertFalse(space.runs[0].released)
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
    fun theSinkWriterAndTheStagedZipAreTheSameBytes() {
        val entries = listOf(entry("A/x.parquet", ByteArray(70_000) { it.toByte() }), entry("B/y.parquet"))
        val memory = MemorySink()
        ArchiveExport.writeZip(memory, entries)
        val space = MemoryExportSpace()
        ArchiveExport.stageZip(space, entries, "z.zip")
        assertContentEquals(memory.bytes(), space.runs.single().files.getValue("z.zip").bytes())
        assertTrue(memory.bytes().size <= ArchiveExport.plannedZipBytes(entries))
    }

    @Test
    fun aZipThatDoesNotFitLeavesNoFileBehind() {
        val entries = listOf(entry("A/x.parquet", ByteArray(1_000)))
        val space = MemoryExportSpace().apply { free = 1_000 }
        assertFailsWith<StorageException> { ArchiveExport.stageZip(space, entries, "z.zip") }
        assertTrue(space.runs.single().deleted)
        assertTrue(space.runs.single().files.isEmpty())
        space.free = ArchiveExport.plannedZipBytes(entries)
        ArchiveExport.stageZip(space, entries, "z.zip")
        assertFalse(space.runs.last().deleted)
    }

    @Test
    fun aCheckpointStopsTheZipBetweenSlicesAndRemovesTheRun() {
        class Stop : RuntimeException()
        val entries = listOf(entry("A/x.parquet", ByteArray(200_000)), entry("B/y.parquet"))
        var calls = 0
        assertFailsWith<Stop> { ArchiveExport.writeZip(MemorySink(), entries) { if (++calls == 3) throw Stop() } }
        assertEquals(3, calls)
        val space = MemoryExportSpace()
        assertFailsWith<Stop> { ArchiveExport.stageZip(space, entries, "z.zip") { throw Stop() } }
        assertTrue(space.runs.single().deleted)
    }

    @Test
    fun aFullDiskIsAStorageFailure() {
        val full = object : ExportSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = throw IllegalStateException("write failed: ENOSPC (No space left on device)")
            override fun close() = Unit
        }
        val failure = assertFailsWith<StorageException> { ArchiveExport.writeZip(full, listOf(entry("A/x.parquet"))) }
        assertTrue(failure.cause?.message.orEmpty().contains("ENOSPC"))
        assertFailsWith<IllegalStateException> {
            ArchiveExport.writeZip(
                object : ExportSink {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = throw IllegalStateException("broken pipe")
                    override fun close() = Unit
                },
                listOf(entry("A/x.parquet"))
            )
        }
    }

    @Test
    fun everyExportClosesTheReadersItOpened() {
        val opened = ArrayList<ClosingReader>()
        val entries = listOf("A/x.parquet", "B/y.parquet").map { path ->
            ExportEntry(path, 0) { ClosingReader(byteArrayOf(4, 5)).also { opened += it } }
        }
        ArchiveExport.stageZip(MemoryExportSpace().apply { free = Long.MAX_VALUE }, entries, "z.zip")
        ArchiveExport.stageCopies(MemoryExportSpace(), entries)
        // Planning opens each entry once, the zip once more, the loose copies once more.
        assertEquals(6, opened.size)
        assertTrue(opened.all { it.closes == 1 })
    }

    private fun record(station: String, name: String, syncedAt: Long) =
        ArchiveRecord(station = station, name = name, bytes = 1, crc32 = 1, sha256 = "x", syncedAtEpochMs = syncedAt, sourceDevice = station)

    /** Two stations' verified finals plus a PAR1-invalid final no record vouches for. */
    private fun twoStationsAndAnOrphan(): InMemoryArchiveStore = InMemoryArchiveStore().apply {
        files["${ArchiveLayout.ARCHIVE}/B/b.parquet"] = byteArrayOf(9)
        files["${ArchiveLayout.ARCHIVE}/A/a.parquet"] = byteArrayOf(8)
        files["${ArchiveLayout.ARCHIVE}/A/orphan.parquet"] = "not parquet".encodeToByteArray()
    }

    private fun zipNames(entries: List<ExportEntry>): List<String> {
        val sink = MemorySink()
        ArchiveExport.writeZip(sink, entries)
        return ParsedZip(sink.bytes()).entries.map { it.name }
    }

    @Test
    fun aStationZipHoldsOnlyThatStationsVerifiedFiles() = kotlinx.coroutines.test.runTest {
        val store = twoStationsAndAnOrphan()
        val a = record("A", "a.parquet", syncedAt = 42)
        val entries = ArchiveExport.archiveEntries(store, listOf(a))
        assertEquals(listOf("A/a.parquet"), zipNames(entries))
        assertEquals(42, entries.single().modifiedEpochMs)
        assertContentEquals(byteArrayOf(8), entries.single().open().read(0, 1))
    }

    @Test
    fun aFinalRemovedAfterTheListingIsSkippedAndCounted() = kotlinx.coroutines.test.runTest {
        val store = twoStationsAndAnOrphan()
        val entries = ArchiveExport.archiveEntries(store, listOf(record("A", "a.parquet", 1), record("B", "b.parquet", 2)))
        // The old format cleanup removes a listed final before the zip is written.
        store.files.remove("${ArchiveLayout.ARCHIVE}/A/a.parquet")
        val left = entries.filter { it.path == "B/b.parquet" }
        assertEquals(ArchiveExport.plannedZipBytes(left), ArchiveExport.plannedZipBytes(entries))

        val sink = MemorySink()
        assertEquals(1, ArchiveExport.writeZip(sink, entries))
        assertEquals(listOf("B/b.parquet"), ParsedZip(sink.bytes()).entries.map { it.name })

        val space = MemoryExportSpace().apply { free = Long.MAX_VALUE }
        assertEquals(1, ArchiveExport.stageZip(space, entries, "z.zip").skipped)
        assertEquals(0, ArchiveExport.stageZip(space, left, "b.zip").skipped)
        assertFalse(space.runs.first().deleted)
        assertContentEquals(sink.bytes(), space.runs.first().files.getValue("z.zip").bytes())

        // With every listed final gone there is nothing to share, so the export fails and leaves no run.
        store.files.remove("${ArchiveLayout.ARCHIVE}/B/b.parquet")
        assertFailsWith<NoSuchElementException> { ArchiveExport.writeZip(MemorySink(), entries) }
        assertFailsWith<NoSuchElementException> { ArchiveExport.stageZip(space, entries, "gone.zip") }
        assertTrue(space.runs.last().deleted)
    }

    @Test
    fun theWholePhoneZipHoldsEveryVerifiedFileAndNoOrphan() = kotlinx.coroutines.test.runTest {
        val store = twoStationsAndAnOrphan()
        val records = listOf(record("B", "b.parquet", 2), record("A", "a.parquet", 1), record("A", "a.parquet", 1))
        assertEquals(listOf("A/a.parquet", "B/b.parquet"), zipNames(ArchiveExport.archiveEntries(store, records)))
    }
}

/** A reader over [bytes] that counts its closes. */
class ClosingReader(private val bytes: ByteArray) : ByteReader {
    var closes = 0
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    override fun close() {
        closes++
    }
}
