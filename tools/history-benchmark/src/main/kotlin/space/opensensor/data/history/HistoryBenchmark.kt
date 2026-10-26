package space.opensensor.data.history

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.MemoryRangeSource
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import java.io.ByteArrayOutputStream

/** A synthetic UTC day of 8,640 rows in 96 files, fixed across before and after runs. */
@State(Scope.Benchmark)
open class HistoryBenchmark {
    private val thing = "benchmark-thing"
    private val day = 20_000L
    private lateinit var sources: List<DailyParquetSource>
    private lateinit var raw: HistorySeriesReader
    private lateinit var derived: HistorySeriesReader
    private lateinit var window: HistoryWindow

    @Setup
    fun setup() {
        val bytes = LinkedHashMap<ArchiveKey, ByteArray>()
        val records = (0 until 96).map { file ->
            val sink = Sink()
            val writer = ParquetWriter(
                sink,
                listOf(
                    ParquetWriter.Column(ParquetIndexer.UTC, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS),
                    ParquetWriter.Column("boot_id_hi", ParquetType.INT64),
                    ParquetWriter.Column("boot_id_lo", ParquetType.INT64),
                    ParquetWriter.Column(ParquetIndexer.SEQ, ParquetType.INT64),
                    ParquetWriter.Column("pms_status", ParquetType.INT32),
                    ParquetWriter.Column(Metrics.PM25.column, ParquetType.INT32),
                    ParquetWriter.Column(Metrics.BOARD_TEMP.column, ParquetType.FLOAT),
                ),
                keyValueMetadata = mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict = null, desc = null, intervalMs = null, thing = thing))
            )
            writer.begin()
            writer.rowGroup(
                listOf(
                    ColumnValues.Int64(LongArray(90) { (day * 86_400 + (file * 90 + it) * 10) * 1_000_000 }, null),
                    ColumnValues.Int64(LongArray(90) { 1 }, null),
                    ColumnValues.Int64(LongArray(90) { 2 }, null),
                    ColumnValues.Int64(LongArray(90) { (file * 90 + it).toLong() }, null),
                    ColumnValues.Int32(IntArray(90) { if (it % 17 == 0) 1 else 4 }, null),
                    ColumnValues.Int32(IntArray(90) { it % 80 }, null),
                    ColumnValues.Float32(FloatArray(90) { 21.5f }, null),
                )
            )
            writer.finish()
            val data = sink.bytes()
            val summary = ParquetFile.wrap(data).use(ParquetIndexer::index)
            ArchiveRecord(
                thing, "part-$file.parquet", data.size.toLong(), file.toLong(), "synthetic", 1, "benchmark",
                indexedAtEpochMs = 1, rows = summary.rows, utcRows = summary.utcRows,
                firstUtcNs = summary.firstUtcNs, lastUtcNs = summary.lastUtcNs
            ).also { bytes[it.key] = data }
        }
        sources = records.map { r -> DailyParquetSource(r) { ParquetFile.wrap(bytes.getValue(r.key)) } }
        val index = Index(records)
        val store = Store(bytes)
        val tiers = ArchiveHistoryTiers(store, index)
        val daily = Sink()
        DailyParquetBuilder.build(thing, day, sources, daily, nowEpochS = (day + 2) * 86_400)
        val derivedBytes = daily.bytes()
        raw = HistorySeriesReader(store, index, tiers, io = Dispatchers.Unconfined)
        derived = HistorySeriesReader(
            store,
            index,
            tiers,
            io = Dispatchers.Unconfined,
            derived = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(derivedBytes, "synthetic-day") }
        )
        window = HistoryWindow.endingAt((day + 1) * 86_400, HistoryRange.DAY, TimeZone.UTC)
        check(
            runBlocking { raw.observe(thing, Metrics.PM25, HistoryRange.DAY, window).first() } ==
                runBlocking { derived.observe(thing, Metrics.PM25, HistoryRange.DAY, window).first() }
        )
    }

    @Benchmark
    open fun aggregatorBefore(): Any = LegacyAggregator(thing).also { agg -> sources.forEach { it.open().use { file -> agg.add(file) } } }.buckets()

    @Benchmark
    open fun aggregatorAfter(): Any = Aggregator(thing).also { agg -> sources.forEach { it.open().use { file -> agg.add(file) } } }.buckets()

    @Benchmark
    open fun dailyBefore(): Any? = LegacyDailyParquetBuilder.build(thing, day, sources, Sink(), nowEpochS = (day + 2) * 86_400)

    @Benchmark
    open fun dailyAfter(): Any? = DailyParquetBuilder.build(thing, day, sources, Sink(), nowEpochS = (day + 2) * 86_400)

    @Benchmark
    open fun deriveBefore(): Any = aggregatorBefore() to dailyBefore()

    @Benchmark
    open fun deriveAfter(): Any = DayPass.run(thing, day, sources, Sink(), nowEpochS = (day + 2) * 86_400)

    @Benchmark
    open fun fromRaw(): Any = runBlocking { raw.observe(thing, Metrics.PM25, HistoryRange.DAY, window).first() }

    @Benchmark
    open fun fromDerived(): Any = runBlocking { derived.observe(thing, Metrics.PM25, HistoryRange.DAY, window).first() }

    private class Sink : ParquetSink {
        private val out = ByteArrayOutputStream()
        override fun write(bytes: ByteArray) { out.write(bytes) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Store(private val data: Map<ArchiveKey, ByteArray>) : ArchiveStore {
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in data
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(data.getValue(key))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = data.keys.filter { thing == null || it.thing == thing }
    }

    private class Index(private val records: List<ArchiveRecord>) : ArchiveIndex {
        override fun observeAll(): Flow<List<ArchiveRecord>> = flowOf(records)
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = flowOf(records.filter { it.thing == thing })
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = records.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) = error("read only")
        override suspend fun remove(key: ArchiveKey) = error("read only")
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = emptyList()
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = records.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = records.filter { it.thing == thing }
        override suspend fun things(): List<String> = listOf("benchmark-thing")
    }
}
