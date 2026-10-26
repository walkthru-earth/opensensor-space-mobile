package space.opensensor.data.history

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetWriter

/**
 * #98. Peak heap of one station-day build, the streaming [DailyParquetBuilder] against the builder
 * that held every decoded column of the day ([PreStreamingDailyParquetBuilder]). The heap is probed
 * after a full GC at every source open and every sink write, so the peak is what the build keeps
 * reachable, not what it allocates on the way. A JVM measurement, the 2 GB phone is still to measure.
 */
class DailyParquetMemoryTest {
    private val board = fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet")

    /** [files] files of [rowsPerFile] rows over [fileSeconds] each, every column of the board file, one UTC day. */
    private fun day(epochDay: Long, rowsPerFile: Int, files: Int = 96, fileSeconds: Long = 900L): List<Pair<ArchiveRecord, ByteArray>> {
        val raw = ParquetFile.wrap(board)
        val station = raw.metadata.footer?.stationId ?: "memory-station"
        val base = raw.leaves.map { raw.readColumn(it) }
        return (0 until files).map { f ->
            val values = raw.leaves.mapIndexed { c, leaf ->
                val col = base[c]
                when (leaf.name) {
                    UtcColumns.EVENT_NS -> ColumnValues.Int64(LongArray(rowsPerFile) { i -> (epochDay * 86_400L + f * fileSeconds) * 1_000_000_000L + i * (fileSeconds * 1_000_000_000L / rowsPerFile) }, null)
                    ParquetIndexer.SEQ -> ColumnValues.Int64(LongArray(rowsPerFile) { i -> f.toLong() * rowsPerFile + i }, null)
                    else -> tile(col, rowsPerFile)
                }
            }
            val sink = MemorySink()
            val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }, keyValueMetadata = raw.metadata.keyValueMetadata)
            writer.begin()
            writer.rowGroup(values)
            writer.finish()
            val bytes = sink.toByteArray()
            val index = ParquetIndexer.index(ParquetFile.wrap(bytes), templates = CoreS3Files)
            ArchiveRecord(
                station, "memory/$f.parquet", bytes.size.toLong(), f.toLong(), "sha-$f", 1, "fixture",
                indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = index.utcRows,
                firstUtcNs = index.firstUtcNs, lastUtcNs = index.lastUtcNs, firstSeq = index.firstSeq, lastSeq = index.lastSeq,
            ) to bytes
        }
    }

    private fun tile(col: ColumnValues, n: Int): ColumnValues {
        val m = col.size
        fun valid() = BooleanArray(n) { !col.isNull(it % m) }.takeIf { v -> v.any { !it } }
        return when (col) {
            is ColumnValues.Int32 -> ColumnValues.Int32(IntArray(n) { col.values[it % m] }, valid())
            is ColumnValues.Int64 -> ColumnValues.Int64(LongArray(n) { col.values[it % m] }, valid())
            is ColumnValues.Float32 -> ColumnValues.Float32(FloatArray(n) { col.values[it % m] }, valid())
            is ColumnValues.Float64 -> ColumnValues.Float64(DoubleArray(n) { col.values[it % m] }, valid())
            is ColumnValues.Binary -> {
                val offsets = IntArray(n + 1)
                for (i in 0 until n) offsets[i + 1] = offsets[i] + if (col.isNull(i % m)) 0 else col.length(i % m)
                val data = ByteArray(offsets[n])
                for (i in 0 until n) if (!col.isNull(i % m)) col.data.copyInto(data, offsets[i], col.offsets[i % m], col.offsets[i % m + 1])
                ColumnValues.Binary(offsets, data, valid())
            }
            else -> error("unexpected column ${col::class}")
        }
    }

    private class Probe {
        var peak = 0L
        private val rt = Runtime.getRuntime()

        fun used(): Long {
            System.gc()
            System.gc()
            return rt.totalMemory() - rt.freeMemory()
        }

        fun sample() {
            peak = maxOf(peak, used())
        }
    }

    private class Measured(val peakBytes: Long, val bytes: ByteArray, val millis: Long)

    /**
     * One build. With [probed] the heap is sampled at every 4th source open and every 16th sink write,
     * enough to catch the old builder holding the day and the new one holding its hour. Without it the
     * build runs untouched and only its time counts.
     */
    private fun measure(files: List<Pair<ArchiveRecord, ByteArray>>, epochDay: Long, streaming: Boolean, probed: Boolean): Measured {
        val probe = Probe()
        var writes = 0
        var opens = 0
        val out = MemorySinkProbe { if (probed && writes++ % 16 == 0) probe.sample() }
        val sources = files.map { (record, bytes) -> DailyParquetSource(record) { if (probed && opens++ % 4 == 0) probe.sample(); ParquetFile.wrap(bytes, record.name) } }
        val station = files.first().first.station
        val baseline = if (probed) probe.used() else 0L
        val start = System.nanoTime()
        if (streaming) {
            DailyParquetBuilder.build(station, epochDay, sources, out, emptyMap(), 0)
        } else {
            PreStreamingDailyParquetBuilder.build(station, epochDay, sources, out, emptyMap(), 0)
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        if (probed) probe.sample()
        // The written bytes count in both peaks alike. Subtract them so the peak is the builder's own.
        return Measured(probe.peak - baseline - out.size, out.toByteArray(), millis)
    }

    /** A [MemorySink] that runs [onWrite] before it stores the bytes and knows its size. */
    private class MemorySinkProbe(private val onWrite: () -> Unit) : ParquetSink {
        private val inner = MemorySink()
        var size = 0L
            private set

        override fun write(bytes: ByteArray) {
            onWrite()
            inner.write(bytes)
            size += bytes.size
        }

        fun toByteArray() = inner.toByteArray()
    }

    private fun report(label: String, rowsPerFile: Int): Pair<Measured, Measured> {
        val epochDay = 20_000L
        val files = day(epochDay, rowsPerFile)
        // Warm both up, so class loading and JIT do not land in the timing.
        repeat(2) {
            measure(files, epochDay, streaming = false, probed = false)
            measure(files, epochDay, streaming = true, probed = false)
        }
        val oldMs = measure(files, epochDay, streaming = false, probed = false).millis
        val newMs = measure(files, epochDay, streaming = true, probed = false).millis
        val old = measure(files, epochDay, streaming = false, probed = true)
        val new = measure(files, epochDay, streaming = true, probed = true)
        println(
            "DailyParquetMemoryTest $label, 96 files × $rowsPerFile rows: peak before ${old.peakBytes / 1024} KiB in $oldMs ms, " +
                "after ${new.peakBytes / 1024} KiB in $newMs ms, output ${new.bytes.size / 1024} KiB",
        )
        assertArrayEquals("$label writes the same bytes", old.bytes, new.bytes)
        return old to new
    }

    @Test
    fun aSourceThatFillsManyHoursIsOpenedTwiceNotOncePerHour() {
        // Once to time its rows, once for the hours it fills. Dropping a source after every hour
        // instead of after its last one writes the same bytes but opens it again for each hour.
        val epochDay = 20_000L
        val files = day(epochDay, rowsPerFile = 360, files = 4, fileSeconds = 6 * 3_600L)
        val opens = IntArray(files.size)
        val sources = files.mapIndexed { s, (record, bytes) -> DailyParquetSource(record) { opens[s]++; ParquetFile.wrap(bytes, record.name) } }
        val out = MemorySink()
        DailyParquetBuilder.build(files.first().first.station, epochDay, sources, out, emptyMap(), 0)
        assertEquals(24, ParquetFile.wrap(out.toByteArray()).metadata.rowGroups.size)
        assertEquals(List(files.size) { 2 }, opens.toList())
    }

    @Test
    fun aTenSecondDayHoldsAboutOneHourOfColumnsNotTheDay() {
        val (old, new) = report("10 s cadence", 90)
        // Measured 5.5x apart alone and 3.6x under a parallel build. Holding the day again gives about 1x.
        assertTrue("before ${old.peakBytes}, after ${new.peakBytes}", new.peakBytes * 2 < old.peakBytes)
    }

    @Test
    fun aOneSecondDayHoldsAboutOneHourOfColumnsNotTheDay() {
        val (old, new) = report("1 s cadence", 900)
        // Measured 2.9x to 4.6x apart, the hour of 1 s columns swings with GC. Holding the day again gives about 1x.
        assertTrue("before ${old.peakBytes}, after ${new.peakBytes}", new.peakBytes * 2 < old.peakBytes)
    }
}
