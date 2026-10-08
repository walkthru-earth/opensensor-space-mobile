package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** D12 in docs/platform-parity-plan.md. One de-duplication key for the tiers, the day chart and the daily file. */
class RowKeyTest {

    private val second = 1_000_000_000L
    private val t0 = 3_600L * second

    /** A small telemetry file. [seq] null leaves out the column, a null entry is a null cell. */
    private fun file(utc: LongArray, seq: Array<Long?>?, bootLo: Long? = 7L): ParquetFile {
        val columns = buildList {
            add(ParquetWriter.Column(ParquetIndexer.UTC, ParquetType.INT64, timestampNanosUtc = true))
            if (bootLo != null) add(ParquetWriter.Column("boot_id_lo", ParquetType.INT64))
            if (seq != null) add(ParquetWriter.Column(ParquetIndexer.SEQ, ParquetType.INT64))
            add(ParquetWriter.Column("imu_temperature_c", ParquetType.FLOAT))
        }
        val values = buildList {
            add(ColumnValues.Int64(utc, null))
            if (bootLo != null) add(ColumnValues.Int64(LongArray(utc.size) { bootLo }, null))
            if (seq != null) add(ColumnValues.Int64(LongArray(seq.size) { seq[it] ?: 0L }, BooleanArray(seq.size) { seq[it] != null }))
            add(ColumnValues.Float32(FloatArray(utc.size) { 40f }, null))
        }
        val sink = MemorySink()
        ParquetWriter(sink, columns, ParquetCodec.UNCOMPRESSED).apply { begin(); rowGroup(values); finish() }
        return ParquetFile.wrap(sink.toByteArray())
    }

    private fun hourRows(vararg files: ParquetFile): Int {
        val agg = Aggregator("station", setOf(Resolution.HOUR))
        files.forEach { agg.add(it) }
        return agg.buckets().sumOf { it.rows }
    }

    @Test
    fun `a sequence identifies the row whatever its time`() {
        assertEquals(RowKey.of(1, 2, 5, t0), RowKey.of(1, 2, 5, t0 + second))
        assertNotEquals(RowKey.of(1, 2, 5, t0), RowKey.of(1, 3, 5, t0))
    }

    @Test
    fun `without a sequence the time identifies the row and never matches a sequence key`() {
        assertEquals(RowKey.of(null, null, null, t0), RowKey.of(0, 0, null, t0))
        assertNotEquals(RowKey.of(0, 0, null, 5), RowKey.of(0, 0, 5, 5))
        val keys = RowKeys()
        assertTrue(keys.firstSight(1, 2, null, t0))
        assertFalse(keys.firstSight(1, 2, null, t0))
        assertTrue(keys.firstSight(1, 3, null, t0), "another boot at the same instant is another row")
    }

    @Test
    fun `tiers drop a repeated file with and without a sequence column`() {
        val utc = longArrayOf(t0, t0 + 10 * second, t0 + 20 * second)
        assertEquals(3, hourRows(file(utc, arrayOf(1L, 2L, 3L)), file(utc, arrayOf(1L, 2L, 3L))))
        // Before D12 the tiers kept both copies when the file had no sequence and the daily file dropped one.
        assertEquals(3, hourRows(file(utc, null), file(utc, null)))
        assertEquals(3, hourRows(file(utc, arrayOf(null, null, null)), file(utc, arrayOf(null, null, null))))
    }

    @Test
    fun `rows of two boots at the same instant both count`() {
        val utc = longArrayOf(t0)
        assertEquals(2, hourRows(file(utc, null, bootLo = 1L), file(utc, null, bootLo = 2L)))
    }
}
