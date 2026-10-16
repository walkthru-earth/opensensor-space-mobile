package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ClockAnchor
import earth.walkthru.aqsync.archive.ClockAnchorStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.FooterCache
import earth.walkthru.aqsync.data.history.parquet.MemoryRangeSource
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #63. The Day view reads a day's derived file when one is current and gives the same points as the
 * raw files, across midnight, in a zone whose day spans two UTC days, with a later sync of the same rows.
 */
class DerivedDayViewTest {
    private val day = 20_000L

    @Test
    fun estimatedOnlyDerivedRowsMatchRawRowsForFullAndFractionalWindows() = runTest {
        assertEstimatedParity(includeRealRows = false)
    }

    @Test
    fun mixedRealAndEstimatedDerivedRowsMatchRawRowsForFullAndFractionalWindows() = runTest {
        assertEstimatedParity(includeRealRows = true)
    }

    private suspend fun assertEstimatedParity(includeRealRows: Boolean) {
        val store = Store()
        val index = Index()
        // Actual all-null UTC device bytes. A durable same-boot receipt places these rows at noon.
        val bytes = fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet")
        val (station, summary) = ParquetFile.wrap(bytes).use { file ->
            (file.metadata.stationId ?: "estimated-station") to ParquetIndexer.index(file)
        }
        assertEquals(0L, summary.utcRows)
        val startS = day * 86_400 + 12 * 3_600
        val receipt = ClockAnchor(station, summary.bootId!!, startS * 1_000_000_000L, summary.firstMonoUs!!)
        val clockAnchors = object : ClockAnchorStore {
            override suspend fun anchors(station: String): List<ClockAnchor> = listOf(receipt).filter { it.station == station }
            override suspend fun put(anchor: ClockAnchor) = error("read only")
        }
        val anchor = TimeEstimate.anchors(emptyList(), listOf(receipt)).getValue(receipt.boot)
        val estimatedTimes = ParquetFile.wrap(bytes).use { file -> file.readColumn(ParquetIndexer.MONO).longValues().map(anchor::estimateNs) }
        suspend fun put(name: String, raw: ByteArray) {
            val record = ArchiveRecord(station, name, raw.size.toLong(), index.rows.value.size.toLong(), "test", 1, "fake")
            store.files[record.key] = raw
            index.put(record)
        }
        put("estimated.parquet", bytes)
        if (includeRealRows) {
            // Another immutable source with distinct sequence identities, its real UTC interleaved
            // five seconds after the estimated rows. Both kinds must survive the shared pass.
            val sink = MemorySink()
            ParquetFile.wrap(bytes).use { file ->
                val mono = file.readColumn(ParquetIndexer.MONO).longValues()
                val values = file.leaves.map { leaf ->
                    val column = file.readColumn(leaf)
                    when (leaf.name) {
                        UtcColumns.EVENT_NS -> ColumnValues.Int64(LongArray(column.size) { anchor.estimateNs(mono[it]) + 5_000_000_000L }, null)
                        ParquetIndexer.SEQ -> ColumnValues.Int64(LongArray(column.size) { column.asLong(it)!! + 10_000 }, null)
                        else -> column
                    }
                }
                val writer = ParquetWriter(sink, file.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }, keyValueMetadata = file.metadata.keyValueMetadata)
                writer.begin()
                writer.rowGroup(values)
                writer.finish()
            }
            put("real.parquet", sink.toByteArray())
        }
        ArchiveIndexRepair(store, index, clockAnchors = clockAnchors, nowEpochMs = { 42L }).backfillAll()
        val estimatedSource = index.rows.value.single { it.name == "estimated.parquet" }
        assertTrue(estimatedSource.hasEstimatedUtc)
        assertEquals(64L, estimatedSource.rows)
        val anchors = TimeEstimate.anchors(index.rows.value.map { it.info() }, listOf(receipt))
        val sink = MemorySink()
        val pass = DayPass.run(station, day, index.rows.value.map { record -> DailyParquetSource(record) { ParquetFile.wrap(store.files.getValue(record.key)) } }, sink, anchors, nowEpochS = (day + 2) * 86_400)
        val daily = assertNotNull(pass.daily)
        assertEquals(if (includeRealRows) 128 else 64, daily.rows)
        val derivedBytes = sink.toByteArray()
        ParquetFile.wrap(derivedBytes).use { file ->
            assertEquals(64, file.readColumn(UtcColumns.EVENT_US).nullCount)
            assertEquals(64L, file.numRows - file.readColumn(DailyParquetBuilder.ESTIMATED_UTC).nullCount)
        }
        var derivedOpens = 0
        val derived = DerivedDayFiles { _, epochDay, _, _ ->
            assertEquals(day, epochDay)
            derivedOpens++
            MemoryRangeSource(derivedBytes, "estimated/$includeRealRows")
        }
        val tiers = ArchiveHistoryTiers(store, index, clockAnchors = clockAnchors)
        val rawReader = HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors)
        val derivedReader = HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors, derived = derived)
        val windows = listOf(
            HistoryWindow(day * 86_400, (day + 1) * 86_400, TimeZone.UTC),
            HistoryWindow(startS + 125, startS + 425, TimeZone.UTC),
        )
        for (window in windows) {
            val fromNs = window.fromS * 1_000_000_000L
            val toNs = window.toS * 1_000_000_000L
            val expectedEstimated = estimatedTimes.count { it in fromNs until toNs }
            val expectedReal = if (includeRealRows) estimatedTimes.count { it + 5_000_000_000L in fromNs until toNs } else 0
            for (metric in listOf(Metrics.PM25, Metrics.BOARD_TEMP)) {
                val raw = rawReader.observe(station, metric, HistoryRange.DAY, window).first()
                store.reads = 0
                val fromDerived = derivedReader.observe(station, metric, HistoryRange.DAY, window).first()
                assertEquals(0, store.reads, "derived estimated rows never fall back to raw")
                assertTrue(raw.points.isNotEmpty())
                assertTrue(raw.estimatedRows > 0, "null UTC rows survive the derived scan")
                assertTrue(raw.points.any { it.estimated })
                assertEquals(includeRealRows, raw.points.any { !it.estimated })
                assertEquals(expectedEstimated, raw.points.count { it.estimated })
                assertEquals(expectedReal, raw.points.count { !it.estimated })
                assertEquals(expectedEstimated.toLong(), raw.estimatedRows)
                assertEquals((expectedEstimated + expectedReal).toLong(), raw.validRows)
                assertEquals(raw.points, fromDerived.points)
                assertEquals(raw.validRows, fromDerived.validRows)
                assertEquals(raw.estimatedRows, fromDerived.estimatedRows)
                assertEquals(raw.mean, fromDerived.mean)
                assertEquals(raw.max, fromDerived.max)
                assertEquals(raw, fromDerived)
            }
        }
        assertEquals(4, derivedOpens)
    }

    @Test
    fun conflictingDuplicatesUseTheBuilderChoiceForFullDaysAndFractionalWindows() = runTest {
        val station = "duplicate-station"
        val store = Store()
        val index = Index()
        fun put(name: String, hours: LongArray, temperatures: FloatArray) {
            val sink = MemorySink()
            val columns = listOf(
                ParquetWriter.Column(UtcColumns.EVENT_NS, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column("boot_id_hi", ParquetType.INT64),
                ParquetWriter.Column("boot_id_lo", ParquetType.INT64),
                ParquetWriter.Column(ParquetIndexer.SEQ, ParquetType.INT64),
                ParquetWriter.Column(Metrics.BOARD_TEMP.column, ParquetType.FLOAT),
            )
            val writer = ParquetWriter(sink, columns, keyValueMetadata = mapOf("station_id" to station))
            writer.begin()
            writer.rowGroup(
                listOf(
                    ColumnValues.Int64(LongArray(hours.size) { (day * 86_400 + hours[it] * 3_600) * 1_000_000_000 }, null),
                    ColumnValues.Int64(LongArray(hours.size) { 1 }, null),
                    ColumnValues.Int64(LongArray(hours.size) { 2 }, null),
                    ColumnValues.Int64(LongArray(hours.size) { it.toLong() }, null),
                    ColumnValues.Float32(temperatures, null),
                )
            )
            writer.finish()
            val bytes = sink.toByteArray()
            val summary = ParquetFile.wrap(bytes).use(ParquetIndexer::index)
            val record = ArchiveRecord(station, name, bytes.size.toLong(), 1, "test", 1, "fake", indexedAtEpochMs = 1, rows = summary.rows, utcRows = summary.utcRows, firstUtcNs = summary.firstUtcNs, lastUtcNs = summary.lastUtcNs)
            store.files[record.key] = bytes
            index.rows.value = index.rows.value + record
        }
        // Timeline order preserves this insertion for equal first UTC. The builder uses a.parquet,
        // whose duplicate sequence 1 is earlier and has a different value.
        put("z.parquet", longArrayOf(1, 3), floatArrayOf(90f, 99f))
        put("a.parquet", longArrayOf(1, 2), floatArrayOf(10f, 20f))
        val sources = index.rows.value.map { record -> DailyParquetSource(record) { ParquetFile.wrap(store.files.getValue(record.key)) } }
        val legacy = LegacyAggregator(station)
        for (source in sources) source.open().use { legacy.add(it) }
        assertEquals(94.5f, legacy.buckets().first { it.resolution == Resolution.DAY }.metrics.getValue(Metrics.BOARD_TEMP.id).mean)
        val sink = MemorySink()
        val pass = DayPass.run(station, day, sources, sink, nowEpochS = 0)
        assertEquals(15f, pass.buckets.first { it.resolution == Resolution.DAY }.metrics.getValue(Metrics.BOARD_TEMP.id).mean)
        val derived = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(sink.toByteArray(), "duplicates") }
        val tiers = ArchiveHistoryTiers(store, index)
        val rawReader = HistorySeriesReader(store, index, tiers)
        val derivedReader = HistorySeriesReader(store, index, tiers, derived = derived)
        val windows = listOf(
            HistoryWindow(day * 86_400, (day + 1) * 86_400, TimeZone.UTC),
            HistoryWindow(day * 86_400 + 9_000, day * 86_400 + 12_600, TimeZone.UTC),
        )
        for (window in windows) {
            val raw = rawReader.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, window).first()
            val fromDerived = derivedReader.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, window).first()
            assertEquals(raw, fromDerived)
        }
        assertEquals(15f, rawReader.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, windows.first()).first().mean)
        assertTrue(rawReader.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, windows.last()).first().points.isEmpty())
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        var reads = 0
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            reads++
            return ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        }
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

    /** The 180 row board file with its UTC spread over 36 hours from 06:00 of [day], rows before [nullBefore] without UTC. */
    private fun respread(nullBefore: Int = 0): ByteArray {
        val raw = ParquetFile.wrap(fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet"))
        val values = raw.leaves.map { leaf ->
            val col = raw.readColumn(leaf)
            if (leaf.name != UtcColumns.EVENT_NS) {
                col
            } else {
                val step = 36 * 3_600L / col.size
                ColumnValues.Int64(LongArray(col.size) { i -> (day * 86_400L + 6 * 3_600L + i * step) * 1_000_000_000L }, BooleanArray(col.size) { it >= nullBefore })
            }
        }
        val sink = MemorySink()
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }, keyValueMetadata = raw.metadata.keyValueMetadata)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    @Test
    fun theDerivedDayGivesTheRawPointsWithoutOpeningARawFile() = runTest {
        val first = respread()
        val station = ParquetFile.wrap(first).metadata.stationId ?: "derived-station"
        val store = Store()
        val index = Index()
        for ((i, bytes) in listOf(first, respread(nullBefore = 70)).withIndex()) {
            val r = ArchiveRecord(station, "station=$station/day/part-$i.parquet", bytes.size.toLong(), i.toLong(), "s$i", 1, "fake")
            store.files[r.key] = bytes
            index.put(r)
        }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index)
        var opened = 0
        val derived = DerivedDayFiles { s, epochDay, sources, anchors ->
            val sink = MemorySink()
            val pass = DayPass.run(s, epochDay, sources.map { r -> DailyParquetSource(r) { ParquetFile.wrap(store.files.getValue(r.key), r.name) } }, sink, anchors, (epochDay + 2) * 86_400, tiers = false)
            pass.daily?.let {
                opened++
                MemoryRangeSource(sink.toByteArray(), "derived/$epochDay")
            }
        }
        val raw = HistorySeriesReader(store, index, tiers)
        val fromDerived = HistorySeriesReader(store, index, tiers, derived = derived)
        // Ends inside the second UTC day, so the window takes rows from both days and both sides of midnight.
        val ends = listOf((day + 1) * 86_400 + 9 * 3_600, (day + 1) * 86_400 + 18 * 3_600)
        for (zone in listOf(TimeZone.UTC, TimeZone.of("Asia/Kolkata"))) {
            for (end in ends) {
                val window = HistoryWindow.endingAt(end, HistoryRange.DAY, zone)
                for (metric in listOf(Metrics.PM25, Metrics.BOARD_TEMP)) {
                    val expected = raw.observe(station, metric, HistoryRange.DAY, window).first()
                    store.reads = 0
                    val actual = fromDerived.observe(station, metric, HistoryRange.DAY, window).first()
                    assertEquals(0, store.reads, "no raw file opened for $zone $end")
                    assertTrue(expected.points.isNotEmpty(), "the window holds rows for $zone $end")
                    assertEquals(expected.points, actual.points, "$metric $zone $end")
                    assertEquals(expected.validRows, actual.validRows)
                    assertEquals(expected.mean, actual.mean)
                    assertEquals(expected.max, actual.max)
                    assertEquals(expected.estimatedRows, actual.estimatedRows)
                }
            }
        }
        assertTrue(opened > 0)
    }

    @Test
    fun aDayWithoutADerivedFileReadsTheRawFiles() = runTest {
        val bytes = respread()
        val station = ParquetFile.wrap(bytes).metadata.stationId ?: "derived-station"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(station, "station=$station/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index)
        val window = HistoryWindow.endingAt((day + 1) * 86_400 + 9 * 3_600, HistoryRange.DAY, TimeZone.UTC)
        val expected = HistorySeriesReader(store, index, tiers).observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        val broken = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(ByteArray(64), "broken") }
        val actual = HistorySeriesReader(store, index, tiers, derived = broken).observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        assertEquals(expected.points, actual.points)
        assertTrue(actual.points.isNotEmpty())

        // A close failure happens after rows were extracted. The raw fallback must still see all
        // their identities instead of dropping keys left behind by the failed cache read.
        var failedCloses = 0
        val failsAfterReading = DerivedDayFiles { s, epochDay, sources, anchors ->
            val sink = MemorySink()
            val pass = DayPass.run(s, epochDay, sources.map { source -> DailyParquetSource(source) { ParquetFile.wrap(store.files.getValue(source.key)) } }, sink, anchors, 0, tiers = false)
            pass.daily?.let {
                object : RangeSource by MemoryRangeSource(sink.toByteArray(), "close-failure/$epochDay") {
                    override fun close() {
                        failedCloses++
                        error("cache close failed")
                    }
                }
            }
        }
        val recovered = HistorySeriesReader(store, index, tiers, derived = failsAfterReading).observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        assertTrue(failedCloses > 0)
        assertEquals(expected, recovered)
    }

    @Test
    fun aSecondReadOfADerivedDayReusesItsFooter() = runTest {
        val bytes = respread()
        val station = ParquetFile.wrap(bytes).metadata.stationId ?: "derived-station"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(station, "station=$station/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index)
        val days = HashMap<Long, ByteArray>()
        for (epochDay in listOf(day, day + 1)) {
            val sink = MemorySink()
            DayPass.run(station, epochDay, index.rows.value.map { s -> DailyParquetSource(s) { ParquetFile.wrap(store.files.getValue(s.key)) } }, sink, emptyMap(), 0, tiers = false)
            days[epochDay] = sink.toByteArray()
        }
        // The same identity as the good file, but the footer in its tail is garbage. Only a reader
        // that already holds the parsed footer can read it.
        var corrupt = false
        val derived = DerivedDayFiles { _, epochDay, _, _ ->
            val good = days[epochDay] ?: return@DerivedDayFiles null
            val source = MemoryRangeSource(good, "derived/$epochDay", "v1")
            if (!corrupt) return@DerivedDayFiles source
            val footerLength = (good[good.size - 8].toInt() and 0xff) or ((good[good.size - 7].toInt() and 0xff) shl 8) or
                ((good[good.size - 6].toInt() and 0xff) shl 16) or ((good[good.size - 5].toInt() and 0xff) shl 24)
            val damaged = good.copyOf().also { it.fill(0x7f, it.size - 8 - footerLength, it.size - 8) }
            object : RangeSource by source {
                override suspend fun readTail(n: Int) = MemoryRangeSource(damaged, "derived/$epochDay", "v1").readTail(n)
            }
        }
        // Both sides of midnight, so the window reads two derived days.
        val window = HistoryWindow(day * 86_400 + 9 * 3_600, (day + 1) * 86_400 + 9 * 3_600, TimeZone.UTC)
        val footers = FooterCache(4)
        val reader = HistorySeriesReader(store, index, tiers, derived = derived, footers = footers)
        store.reads = 0
        val expected = reader.observe(station, Metrics.PM25, HistoryRange.DAY, window).first()
        assertTrue(expected.points.isNotEmpty())
        assertEquals(2, footers.size)

        corrupt = true
        store.reads = 0
        val cold = HistorySeriesReader(store, index, tiers, derived = derived).observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, window).first()
        assertTrue(store.reads > 0, "without the footer the damaged tail falls back to the raw file")
        store.reads = 0
        val warm = reader.observe(station, Metrics.BOARD_TEMP, HistoryRange.DAY, window).first()
        assertEquals(0, store.reads, "the cached footer serves the second read")
        assertEquals(cold, warm)
        assertEquals(2, footers.size)
    }
}
