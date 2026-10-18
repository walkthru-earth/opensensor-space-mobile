package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun dailyFixture(open: (ByteArray) -> ParquetFile = { ParquetFile.wrap(it) }): Pair<DailyParquetSource, TimeEstimate.Anchor> {
    val bytes = fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet")
    val file = ParquetFile.wrap(bytes)
    val index = ParquetIndexer.index(file)
    val anchor = TimeEstimate.Anchor(index.bootId!!, 1_788_912_000_123_456_000L, index.lastMonoUs!!, "clock:receipt")
    val record = ArchiveRecord(
        file.metadata.stationId ?: "station-a", "raw.parquet", 1, 123, "hash", 1, "device",
        indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = 0,
        firstMonoUs = index.firstMonoUs, lastMonoUs = index.lastMonoUs,
        estFirstUtcNs = anchor.estimateNs(index.firstMonoUs!!), estLastUtcNs = anchor.utcNs, estAnchorFile = anchor.sourceFile,
    )
    return DailyParquetSource(record) { open(bytes) } to anchor
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

/** Counts the source readers a build holds open at once. */
private class OpenSources {
    var open = 0
    var maxOpen = 0
    var opened = 0
    var closed = 0

    fun reader(bytes: ByteArray): ByteReader = object : ByteReader {
        private val inner = ByteArrayReader(bytes)
        private var isOpen = true

        init {
            opened++
            open++
            maxOpen = maxOf(maxOpen, open)
        }

        override val size: Long get() = inner.size
        override fun read(offset: Long, length: Int): ByteArray {
            check(isOpen) { "reader is closed" }
            return inner.read(offset, length)
        }
        override fun close() {
            if (!isOpen) return
            isOpen = false
            open--
            closed++
        }
    }
}

class DailyParquetBuilderTest {
    @Test
    fun aCancelledSinkCancelsThePass() {
        val (source, anchor) = dailyFixture()
        val sink = object : ParquetSink {
            override fun write(bytes: ByteArray): Unit = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            DayPass.run(source.record.station, StationDays.dayOf(anchor.utcNs), listOf(source), sink, mapOf(anchor.boot to anchor), 0)
        }
    }

    @Test
    fun aFailedDailySinkKeepsTiersWithoutDecodingAgain() {
        val (fixture, anchor) = dailyFixture()
        val day = StationDays.dayOf(anchor.utcNs)
        val anchors = mapOf(anchor.boot to anchor)
        val expected = DayPass.run(fixture.record.station, day, listOf(fixture), null, anchors, 0).buckets
        for (failOnFlush in listOf(false, true)) {
            val counter = OpenSources()
            val (source, _) = dailyFixture { ParquetFile.open(counter.reader(it), "raw.parquet") }
            val failure = IllegalStateException("cache unavailable")
            val sink = object : ParquetSink {
                override fun write(bytes: ByteArray) { if (!failOnFlush) throw failure }
                override fun flush(): Unit = throw failure
            }
            val pass = DayPass.run(source.record.station, day, listOf(source), sink, anchors, 0)
            assertEquals(expected, pass.buckets)
            assertNull(pass.daily)
            assertEquals(failure, pass.dailyError)
            assertEquals(1, counter.opened, "sink failure must keep the original decode")
            assertEquals(1, counter.closed)
        }
    }

    @Test
    fun aDayOfManySourcesHoldsOneSourceOpenAtATimeAndClosesEach() {
        val counter = OpenSources()
        val (source, anchor) = dailyFixture { ParquetFile.open(counter.reader(it), "raw.parquet") }
        val sources = (0 until 12).map { n -> source.copy(record = source.record.copy(name = "raw-$n.parquet")) }
        val result = assertNotNull(
            DailyParquetBuilder.build(source.record.station, StationDays.dayOf(anchor.utcNs), sources, DailyBuffer(), mapOf(anchor.boot to anchor), anchor.utcNs / 1_000_000_000L + 100_000L)
        )
        assertEquals(12, result.sources.size, "every source stays in the manifest")
        assertEquals(12, counter.opened)
        assertEquals(12, counter.closed)
        assertEquals(0, counter.open)
        assertEquals(1, counter.maxOpen, "a day never holds two source files at once")
    }

    @Test
    fun aSourceThatFailsToOpenLeavesEarlierSourcesClosed() {
        val counter = OpenSources()
        var calls = 0
        val (source, anchor) = dailyFixture { bytes ->
            if (++calls == 3) throw ParquetFormatException("unreadable")
            ParquetFile.open(counter.reader(bytes), "raw.parquet")
        }
        val sources = (0 until 5).map { n -> source.copy(record = source.record.copy(name = "raw-$n.parquet")) }
        assertFailsWith<ParquetFormatException> {
            DailyParquetBuilder.build(source.record.station, StationDays.dayOf(anchor.utcNs), sources, DailyBuffer(), mapOf(anchor.boot to anchor), anchor.utcNs / 1_000_000_000L)
        }
        assertEquals(2, counter.opened)
        assertEquals(0, counter.open, "the sources read before the failure are closed")
    }

    @Test
    fun realUnsyncedRowsRepartitionAcrossMidnightWithMicrosecondPrecisionAndNoDuplicates() {
        val (source, anchor) = dailyFixture()
        val anchorDay = StationDays.dayOf(anchor.utcNs)
        var totalRows = 0L
        val expected = source.open().use { it.readColumn(ParquetIndexer.MONO) }.longValues().map(anchor::estimateNs).sorted()
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
            assertEquals(file.numRows.toInt(), file.readColumn(UtcColumns.EVENT_US).nullCount)
            assertEquals(ParquetLogicalType.TimeUnit.MICROS, file.metadata.leaf(DailyParquetBuilder.ESTIMATED_UTC)!!.timestampUtcUnit)
            // Stored in microseconds, compared on the nanosecond timeline.
            val times = file.readColumn(DailyParquetBuilder.ESTIMATED_UTC).longValues().map { it * 1_000L }
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
        assertEquals(64, source.open().use { it.readColumn(UtcColumns.EVENT_NS) }.nullCount, "raw device UTC stays null")
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

    @Test
    fun anEstimateOfLongMinValueIsKeptAndAMissingOneIsInvalid() {
        // No value of the Long range means "no estimate": validity is its own array.
        val anchor = TimeEstimate.Anchor("boot", Long.MIN_VALUE, 0, "receipt")
        val mono = LongColumn(longArrayOf(0, 5, -1), booleanArrayOf(true, false, true))
        val estimated = DailyParquetBuilder.estimates(3, mono, anchor)
        assertTrue(estimated.has(0))
        assertEquals(Long.MIN_VALUE, estimated.values[0])
        // No monotonic time, and a negative one the anchor refuses.
        assertTrue(!estimated.has(1))
        assertTrue(!estimated.has(2))
    }
}
