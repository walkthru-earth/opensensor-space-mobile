package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CellTrackReaderTest {
    private val thing = "thing-a"
    private val startS = 1_790_000_000L
    private val home = 0x887576970bfffffL
    private val away = 0x887576914bfffffL

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.toList()
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.thing == thing } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    /** One row every 10 s from [firstS], each with the [cells] and [sources] of its index. Null is a null cell. */
    private fun file(firstS: Long, cells: List<Long?>, sources: List<Int?>, withLocation: Boolean = true): ByteArray {
        val n = cells.size
        val sink = MemorySink()
        val columns = listOf(ParquetWriter.Column(UtcColumns.EVENT_US, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS)) +
            if (withLocation) {
                listOf(ParquetWriter.Column(CellTrackReader.CELL, ParquetType.INT64), ParquetWriter.Column(CellTrackReader.SOURCE, ParquetType.INT32))
            } else {
                emptyList()
            }
        val w = ParquetWriter(sink, columns, ParquetCodec.UNCOMPRESSED)
        w.begin()
        val values = listOf<ColumnValues>(ColumnValues.Int64(LongArray(n) { (firstS + 10L * it) * 1_000_000L }, null)) +
            if (withLocation) {
                listOf(
                    ColumnValues.Int64(LongArray(n) { cells[it] ?: 0L }, BooleanArray(n) { cells[it] != null }),
                    ColumnValues.Int32(IntArray(n) { sources[it] ?: 0 }, BooleanArray(n) { sources[it] != null }),
                )
            } else {
                emptyList()
            }
        w.rowGroup(values)
        w.finish()
        return sink.toByteArray()
    }

    private suspend fun put(store: Store, index: Index, name: String, bytes: ByteArray, firstS: Long, rows: Int) {
        val record = ArchiveRecord(
            thing = thing, name = name, bytes = bytes.size.toLong(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake",
            indexedAtEpochMs = 2, rows = rows.toLong(), utcRows = rows.toLong(),
            firstUtcNs = firstS * NS_PER_S, lastUtcNs = (firstS + 10L * (rows - 1)) * NS_PER_S,
        )
        store.files[record.key] = bytes
        index.put(record)
    }

    @Test
    fun aMovingFileGivesItsCellsAndItsRowsWithNoFix() = runTest {
        val store = Store()
        val index = Index()
        val gnss = LocationSource.GNSS.wire
        val bytes = file(startS, listOf(home, home, null, away), listOf(gnss, gnss, null, gnss))
        put(store, index, "thing=$thing/year=2026/month=09/day=21/a.parquet", bytes, startS, 4)
        val track = CellTrackReader(store, index).read(thing, startS * NS_PER_S, (startS + 100) * NS_PER_S)
        assertEquals(listOf(home, null, away), track.spans.map { it.cell })
        assertEquals(1, track.noFixRows)
        assertEquals(away, track.lastFix?.cell)
    }

    @Test
    fun aFileWithoutLocationColumnsHasNoFixAndAMissingFileIsLeftOut() = runTest {
        val store = Store()
        val index = Index()
        put(store, index, "thing=$thing/year=2026/month=09/day=21/a.parquet", file(startS, listOf(null, null), listOf(null, null), withLocation = false), startS, 2)
        put(store, index, "thing=$thing/year=2026/month=09/day=21/b.parquet", ByteArray(8), startS + 20, 1)
        val track = CellTrackReader(store, index).read(thing, startS * NS_PER_S, (startS + 100) * NS_PER_S)
        assertTrue(track.fixes.isEmpty())
        assertEquals(2, track.noFixRows)
    }

    @Test
    fun theWindowLeavesOutOlderRows() = runTest {
        val store = Store()
        val index = Index()
        val gnss = LocationSource.GNSS.wire
        put(store, index, "thing=$thing/year=2026/month=09/day=21/a.parquet", file(startS, listOf(home, away), listOf(gnss, gnss)), startS, 2)
        val reader = CellTrackReader(store, index, io = StandardTestDispatcher(testScheduler))
        val nowNs = (startS + 15) * NS_PER_S
        assertEquals(listOf(away), reader.observe(thing, spanNs = 10 * NS_PER_S, nowNs = { nowNs }).first().cells)
        assertEquals(listOf(home, away), reader.read(thing, 0, nowNs).cells)
    }

    @Test
    fun theWindowMovesOnWithTimeAndNoNewFile() = runTest {
        val store = Store()
        val index = Index()
        val gnss = LocationSource.GNSS.wire
        put(store, index, "thing=$thing/year=2026/month=09/day=21/a.parquet", file(startS, listOf(home, away), listOf(gnss, gnss)), startS, 2)
        val reader = CellTrackReader(store, index, io = StandardTestDispatcher(testScheduler))
        // The clock follows the test's virtual time. After one tick the away row is older than the span.
        val nowNs = { (startS + 15) * NS_PER_S + testScheduler.currentTime * 1_000_000 }
        val seen = reader.observe(thing, spanNs = 10 * NS_PER_S, nowNs = nowNs, tickMs = 10_000).take(2).map { it.cells }.toList()
        assertEquals(listOf(listOf(away), emptyList()), seen)
    }

    @Test
    fun aFixedWindowReadsAgainOnANewFile() = runTest {
        val store = Store()
        val index = Index()
        val gnss = LocationSource.GNSS.wire
        put(store, index, "thing=$thing/year=2026/month=09/day=21/a.parquet", file(startS, listOf(home), listOf(gnss)), startS, 1)
        val reader = CellTrackReader(store, index, io = StandardTestDispatcher(testScheduler))
        val window = reader.observeWindow(thing, startS * NS_PER_S, (startS + 100) * NS_PER_S)
        assertEquals(listOf(home), window.first().cells)
        put(store, index, "thing=$thing/year=2026/month=09/day=21/b.parquet", file(startS + 50, listOf(away), listOf(gnss)), startS + 50, 1)
        assertEquals(listOf(home, away), window.first().cells)
        // A row past the window's end stays out.
        put(store, index, "thing=$thing/year=2026/month=09/day=21/c.parquet", file(startS + 200, listOf(home), listOf(gnss)), startS + 200, 1)
        assertEquals(2, window.first().spans.size)
    }

    private companion object {
        const val NS_PER_S = 1_000_000_000L
    }
}
