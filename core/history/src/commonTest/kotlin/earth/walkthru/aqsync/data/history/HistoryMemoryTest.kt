package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import earth.walkthru.aqsync.storage.MemoryPressure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The memory side of #61 in History: the in-memory tiers stay within their byte budget and give it
 * back on memory pressure, and every reader History opens is closed exactly once.
 */
class HistoryMemoryTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val firstDay = 20_000L // 2024-10-04, UTC epoch day
    private val board = fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")

    /** A [ByteReader] that counts its closes. */
    private class CountingReader(bytes: ByteArray) : ByteReader {
        private val inner = ByteArrayReader(bytes)
        var closes = 0
        override val size: Long get() = inner.size
        override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length)
        override fun close() {
            closes++
        }
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        val opened = ArrayList<CountingReader>()
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader =
            CountingReader(files[key] ?: throw NoSuchElementException(key.name)).also { opened += it }
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.filter { station == null || it.station == station }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.indexed }
        override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
    }

    private fun record(name: String, bytes: ByteArray, crc: Long = 1, owner: String = station) =
        ArchiveRecord(station = owner, name = name, bytes = bytes.size.toLong(), crc32 = crc, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake")

    /** The board fixture's 105 rows spread over one UTC day, so every 15 minute bucket of the day has a row. */
    private fun dayFile(epochDay: Long): ByteArray {
        val raw = ParquetFile.wrap(board)
        val values = raw.leaves.map { leaf ->
            val col = raw.readColumn(leaf)
            if (leaf.name != ParquetIndexer.UTC) {
                col
            } else {
                val step = 86_400L / col.size
                ColumnValues.Int64(LongArray(col.size) { i -> (epochDay * 86_400L + i * step) * 1_000_000_000L }, null)
            }
        }
        val sink = MemorySink()
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, it.isTimestampNanosUtc) }, keyValueMetadata = raw.metadata.keyValueMetadata)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    /** [days] consecutive station-days, one indexed file each. */
    private suspend fun archive(days: Int): Pair<Store, Index> {
        val store = Store()
        val index = Index()
        for (d in 0 until days) {
            val bytes = dayFile(firstDay + d)
            val r = record("station=$station/day-$d/data_$d.parquet", bytes, crc = d.toLong())
            store.files[r.key] = bytes
            index.put(r)
        }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        return store to index
    }

    // Decoding 400 days is CPU bound and can pass the default minute while the full gate runs in parallel.
    @Test
    fun tiersStayWithinTheirBudgetAndGiveItBackOnMemoryPressure() = runTest(timeout = 5.minutes) {
        // A day of this fixture estimates about 63 KB, so 8 MiB holds about 133 days and 200 overflow it.
        val days = 200
        val (store, index) = archive(days)
        val tiers = ArchiveHistoryTiers(store, index)
        val budget = ArchiveHistoryTiers.DEFAULT_BUDGET_BYTES

        assertEquals(days, tiers.observeDays(station).first().count)
        val full = tiers.cachedBytes()
        assertTrue("cached $full of $budget", full in (budget / 2 + 1)..budget)
        assertTrue("the budget bounded the cache", tiers.cachedDays() in 1 until days)

        tiers.trim(MemoryPressure.Level.Moderate)
        assertTrue(tiers.cachedBytes() <= budget / 2)
        assertTrue(tiers.cachedDays() > 0)

        tiers.trim(MemoryPressure.Level.Critical)
        assertEquals(0, tiers.cachedDays())
        assertEquals(0L, tiers.cachedBytes())

        // Evicted days are rebuilt from the archive on the next read.
        assertEquals(days, tiers.observeDays(station).first().count)
        assertTrue(tiers.cachedBytes() in 1..budget)
    }

    @Test
    fun tiersCloseEveryReaderTheyOpenOnce() = runTest {
        val (store, index) = archive(3)
        // A file whose footer does not parse is skipped, and its reader is closed too.
        val broken = record("station=$station/day-x/data_x.parquet", board.copyOf(board.size - 8), crc = 99)
        store.files[broken.key] = board.copyOf(board.size - 8)
        index.put(broken.copy(indexedAtEpochMs = 42L, rows = 1, utcRows = 1, firstUtcNs = firstDay * StationDays.DAY_NS, lastUtcNs = firstDay * StationDays.DAY_NS + 1))
        store.opened.clear()

        val tiers = ArchiveHistoryTiers(store, index)
        assertEquals(3, tiers.observeDays(station).first().count)

        assertEquals(4, store.opened.size)
        assertEquals(List(4) { 1 }, store.opened.map { it.closes })
    }

    @Test
    fun theRowReaderClosesEveryReaderItOpensOnce() = runTest {
        val (store, index) = archive(2)
        store.opened.clear()
        val reader = HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index))
        val toS = (firstDay + 1) * 86_400L

        val day = reader.observe(station, Metrics.PM25, HistoryRange.DAY, HistoryWindow.endingAt(toS, HistoryRange.DAY, TimeZone.UTC)).first()

        assertTrue(day.points.isNotEmpty())
        assertTrue(store.opened.isNotEmpty())
        assertTrue(store.opened.all { it.closes == 1 })
    }

    @Test
    fun indexRepairClosesTheReaderOnceWhateverTheOutcome() {
        val repair = ArchiveIndexRepair(Store(), Index(), nowEpochMs = { 42L })
        val good = CountingReader(board)
        val indexed = repair.indexed(record("data.parquet", board), { good })
        assertNull(indexed.indexError)
        assertEquals(1, good.closes)

        // The footer names another station: the check throws inside the open file.
        val foreign = CountingReader(board)
        val refused = repair.indexed(record("data.parquet", board, owner = "someone-else"), { foreign })
        assertNotNull(refused.indexError)
        assertEquals(1, foreign.closes)

        // A footer that does not parse is closed by ParquetFile.open itself.
        val truncated = CountingReader(board.copyOf(board.size - 8))
        val unreadable = repair.indexed(record("data.parquet", board), { truncated })
        assertNotNull(unreadable.indexError)
        assertEquals(1, truncated.closes)
    }
}
