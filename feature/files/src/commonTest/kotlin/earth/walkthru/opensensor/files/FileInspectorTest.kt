package earth.walkthru.opensensor.files

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #98. The inspector formats a page of rows at a time, never every row of the file. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileInspectorTest {
    private val name = "station=s/year=2026/month=09/day=17/data_0900_b_1-150-1.parquet"

    /** Three row groups of 50 rows. `row` is the row index, `pm` is null on every 7th row. */
    private val bytes: ByteArray = run {
        val parts = ArrayList<ByteArray>()
        val sink = object : ParquetSink {
            override fun write(bytes: ByteArray) {
                parts += bytes.copyOf()
            }
        }
        val writer = ParquetWriter(
            sink,
            listOf(
                ParquetWriter.Column("row", ParquetType.INT64),
                ParquetWriter.Column("pm", ParquetType.FLOAT),
                ParquetWriter.Column("label", ParquetType.BYTE_ARRAY),
            ),
        )
        writer.begin()
        for (g in 0 until 3) {
            val base = g * 50
            val labels = (0 until 50).map { "r${base + it}".encodeToByteArray() }
            val offsets = IntArray(51)
            for (i in 0 until 50) offsets[i + 1] = offsets[i] + labels[i].size
            val data = ByteArray(offsets[50])
            labels.forEachIndexed { i, l -> l.copyInto(data, offsets[i]) }
            writer.rowGroup(
                listOf(
                    ColumnValues.Int64(LongArray(50) { (base + it).toLong() }, null),
                    ColumnValues.Float32(FloatArray(50) { (base + it) / 2f }, BooleanArray(50) { (base + it) % 7 != 0 }),
                    ColumnValues.Binary(offsets, data, null),
                ),
            )
        }
        writer.finish()
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        out
    }

    @Test
    fun aColumnSummaryFormatsOnlyItsFirstAndLastValue() {
        val col = ParquetFile.wrap(bytes).readColumn("row")
        var formatted = 0
        val summary = FileInspector.summarize("row", ParquetType.INT64, null, col, null, null) { c, i ->
            formatted++
            (c as ColumnValues.Int64).values[i].toString()
        }
        assertEquals(2, formatted, "150 rows, two formatted")
        assertEquals("0", summary.first)
        assertEquals("149", summary.last)
        assertEquals("0", summary.min)
        assertEquals("149", summary.max)
    }

    @Test
    fun aPageFormatsOnlyItsOwnRowsKeyedByRowIndex() {
        val pf = ParquetFile.wrap(bytes)
        val asked = ArrayList<Int>()
        // Rows 40 until 60 straddle the first two row groups.
        val page = FileInspector.page(pf, from = 40, size = 20) { c, i ->
            asked += i
            when (c) {
                is ColumnValues.Int64 -> c.values[i].toString()
                is ColumnValues.Float32 -> c.values[i].toString()
                is ColumnValues.Binary -> c.string(i) ?: ""
                else -> "?"
            }
        }
        assertEquals(40L, page.from)
        assertEquals(60L, page.to)
        assertEquals(150L, page.totalRows)
        assertEquals(listOf("row", "pm", "label"), page.columns)
        assertEquals((40 until 60).map { it.toString() }, page.rows.map { it[0] })
        assertEquals((40 until 60).map { "r$it" }, page.rows.map { it[2] })
        // Rows 42, 49 and 56 have no pm. They are shown, not formatted.
        assertEquals(NO_VALUE, page.rows[2][1])
        assertEquals(20 * 3 - 3, asked.size, "only the page's cells are formatted")
        // Indexes are inside each row group, 40..49 of the first and 0..9 of the second.
        assertEquals(((40 until 50) + (0 until 10)).toSet(), asked.toSet())
    }

    @Test
    fun aPageReadsOnlyTheRowGroupsItOverlaps() {
        val offsets = ArrayList<Long>()
        val reader = object : ByteReader {
            private val inner = ByteArrayReader(bytes)
            override val size: Long get() = inner.size

            override fun read(offset: Long, length: Int): ByteArray {
                offsets += offset
                return inner.read(offset, length)
            }
        }
        val pf = ParquetFile.open(reader)
        val third = pf.metadata.rowGroups[2].columns.minOf { it.firstPageOffset }
        offsets.clear()
        FileInspector.page(pf, from = 40, size = 20)
        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.all { it < third }, "rows 40 until 60 never read the third row group")
    }

    @Test
    fun aPagePastTheEndShowsTheLastRows() {
        val pf = ParquetFile.wrap(bytes)
        val last = FileInspector.page(pf, from = 1_000, size = 20)
        assertEquals(130L, last.from)
        assertEquals((130 until 150).map { it.toString() }, last.rows.map { it[0] })
        assertEquals(0L, FileInspector.page(pf, from = -5, size = 20).from)
    }

    private class Store(val finals: Map<ArchiveKey, ByteArray>) : ArchiveStore {
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = error("read only")
        override suspend fun exists(key: ArchiveKey): Boolean = key in finals
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(finals.getValue(key))
        override suspend fun commit(key: ArchiveKey) = error("read only")
        override suspend fun listFinal(station: String?): List<ArchiveKey> = finals.keys.toList()
    }

    @Test
    fun theControllerPagesTheLoadedFileAndForgetsThePageOnANewFile() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val controller = FileMetricsController(CoroutineScope(backgroundScope.coroutineContext + io), Store(mapOf(ArchiveKey("s", name) to bytes)), io)
        controller.page(0)
        assertEquals(RowsState.Idle, controller.rows.value, "nothing is loaded yet")
        controller.load("s", name)
        assertIs<FileMetricsState.Loaded>(controller.state.value)
        controller.page(100)
        val shown = assertIs<RowsState.Shown>(controller.rows.value).page
        assertEquals(100L, shown.from)
        assertEquals(FileInspector.PAGE_ROWS, shown.rows.size)
        controller.load("s", "station=s/year=2026/month=09/day=17/data_1000_b_2-3-1.parquet")
        assertEquals(RowsState.Idle, controller.rows.value)
    }

    @Test
    fun aPageThatCannotBeReadEndsAsUnavailableNotAForeverSpinner() = runTest {
        // The summary loaded, then the file went away before its rows were asked for.
        val io = UnconfinedTestDispatcher(testScheduler)
        val finals = mutableMapOf(ArchiveKey("s", name) to bytes)
        val controller = FileMetricsController(CoroutineScope(backgroundScope.coroutineContext + io), Store(finals), io)
        controller.load("s", name)
        assertIs<FileMetricsState.Loaded>(controller.state.value)
        finals.clear()
        controller.page(40)
        assertEquals(RowsState.Unavailable(40), controller.rows.value)
        finals[ArchiveKey("s", name)] = bytes
        controller.page(40)
        assertEquals(40L, assertIs<RowsState.Shown>(controller.rows.value).page.from)
    }
}
