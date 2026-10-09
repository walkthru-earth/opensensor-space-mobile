package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun dailyFixture(): Pair<DailyParquetSource, TimeEstimate.Anchor> {
    val file = ParquetFile.wrap(fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet"))
    val index = ParquetIndexer.index(file)
    val anchor = TimeEstimate.Anchor(index.bootId!!, 1_788_912_000_123_456_000L, index.lastMonoUs!!, "clock:receipt")
    val record = ArchiveRecord(
        file.metadata.stationId ?: "station-a", "raw.parquet", 1, 123, "hash", 1, "device",
        indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = 0,
        firstMonoUs = index.firstMonoUs, lastMonoUs = index.lastMonoUs,
        estFirstUtcNs = anchor.estimateNs(index.firstMonoUs!!), estLastUtcNs = anchor.utcNs, estAnchorFile = anchor.sourceFile,
    )
    return DailyParquetSource(record, file) to anchor
}

private class DailyBuffer : ParquetSink {
    private val parts = mutableListOf<ByteArray>()
    override fun write(bytes: ByteArray) { parts += bytes.copyOf() }
    fun bytes(): ByteArray {
        val bytes = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(bytes, offset); offset += part.size }
        return bytes
    }
}

class DailyParquetBuilderTest {
    @Test
    fun realUnsyncedRowsRepartitionAcrossMidnightWithMicrosecondPrecisionAndNoDuplicates() {
        val (source, anchor) = dailyFixture()
        val anchorDay = StationDays.dayOf(anchor.utcNs)
        var totalRows = 0L
        val expected = source.file.readColumn(ParquetIndexer.MONO).longValues().map(anchor::estimateNs).sorted()
        val actual = mutableListOf<Long>()
        for (day in anchorDay - 1..anchorDay) {
            val sink = DailyBuffer()
            val result = assertNotNull(
                DailyParquetBuilder.build(
                    source.record.station,
                    day,
                    listOf(source, source.copy(record = source.record.copy(name = "duplicate.parquet"))),
                    sink,
                    mapOf(anchor.boot to anchor),
                    anchor.utcNs / 1_000_000_000L + 100_000L,
                )
            )
            val file = ParquetFile.wrap(sink.bytes())
            totalRows += file.numRows
            assertEquals(result.rows.toLong(), file.numRows)
            assertEquals(file.numRows.toInt(), file.readColumn(ParquetIndexer.UTC).nullCount)
            assertTrue(file.metadata.leaf(DailyParquetBuilder.ESTIMATED_UTC)!!.isTimestampNanosUtc)
            val times = file.readColumn(DailyParquetBuilder.ESTIMATED_UTC).longValues().toList()
            assertTrue(times.all { StationDays.dayOf(it) == day })
            assertEquals(times.sorted(), times)
            actual += times
            assertTrue(result.complete)
            assertEquals(result.rows, result.sources.sumOf { it.rowsUsed })
            assertTrue(result.sources.all { it.timeAnchor == anchor.sourceFile })
        }
        assertEquals(64L, totalRows)
        assertEquals(expected, actual)
        assertEquals(123_456_000L, actual.last() % 1_000_000_000L)
        assertEquals(64, source.file.readColumn(ParquetIndexer.UTC).nullCount, "raw device UTC stays null")
    }

    @Test
    fun rebootCannotBorrowAnAnchorEvenWhenTheIndexIsStale() {
        val (source, anchor) = dailyFixture()
        val wrongBoot = anchor.copy(boot = "0".repeat(32))
        assertNull(
            DailyParquetBuilder.build(
                source.record.station,
                StationDays.dayOf(anchor.utcNs),
                listOf(source),
                DailyBuffer(),
                mapOf(anchor.boot to wrongBoot),
                anchor.utcNs / 1_000_000_000L,
            )
        )
    }
}
