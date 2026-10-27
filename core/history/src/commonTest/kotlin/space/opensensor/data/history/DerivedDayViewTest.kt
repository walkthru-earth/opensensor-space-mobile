package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import space.opensensor.airquality.EpaPm25Correction
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.ClockAnchor
import space.opensensor.archive.ClockAnchorStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.FooterCache
import space.opensensor.data.history.parquet.MemoryRangeSource
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.data.history.parquet.RangeSource
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
        val bytes = withCoreS3Dict(fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet"))
        val (thing, summary) = ParquetFile.wrap(bytes).use { file ->
            (file.metadata.footer?.thingId ?: "estimated-thing") to ParquetIndexer.index(file, templates = CoreS3Files)
        }
        assertEquals(0L, summary.utcRows)
        val startS = day * 86_400 + 12 * 3_600
        val receipt = ClockAnchor(thing, summary.bootId!!, startS * 1_000_000_000L, summary.firstMonoUs!!)
        val clockAnchors = object : ClockAnchorStore {
            override suspend fun anchors(thing: String): List<ClockAnchor> = listOf(receipt).filter { it.thing == thing }
            override suspend fun put(anchor: ClockAnchor) = error("read only")
        }
        val anchor = TimeEstimate.anchors(emptyList(), listOf(receipt)).getValue(receipt.boot)
        val estimatedTimes = ParquetFile.wrap(bytes).use { file -> file.readColumn(ParquetIndexer.MONO).longValues().map(anchor::estimateNs) }
        suspend fun put(name: String, raw: ByteArray) {
            val record = ArchiveRecord(thing, name, raw.size.toLong(), index.rows.value.size.toLong(), "test", 1, "fake")
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
        ArchiveIndexRepair(store, index, clockAnchors = clockAnchors, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val estimatedSource = index.rows.value.single { it.name == "estimated.parquet" }
        assertTrue(estimatedSource.hasEstimatedUtc)
        assertEquals(64L, estimatedSource.rows)
        val anchors = TimeEstimate.anchors(index.rows.value.map { it.info() }, listOf(receipt))
        val sink = MemorySink()
        val pass = DayPass.run(thing, day, index.rows.value.map { record -> DailyParquetSource(record) { ParquetFile.wrap(store.files.getValue(record.key)) } }, sink, anchors, nowEpochS = (day + 2) * 86_400, templates = CoreS3Files)
        val daily = assertNotNull(pass.daily)
        assertEquals(if (includeRealRows) 128 else 64, daily.rows)
        val derivedBytes = sink.toByteArray()
        val estimatedUs = ParquetFile.wrap(derivedBytes).use { file ->
            assertEquals(64, file.readColumn(UtcColumns.EVENT_US).nullCount)
            val est = file.readColumn(DailyParquetBuilder.ESTIMATED_UTC)
            assertEquals(ParquetLogicalType.TimeUnit.MICROS, file.leaf(DailyParquetBuilder.ESTIMATED_UTC).timestampUtcUnit)
            assertEquals(64L, file.numRows - est.nullCount)
            (0 until est.size).mapNotNull { est.asLong(it) }
        }
        // Each estimated row is stored in microseconds and reads back at estimated_us * 1000.
        assertEquals(estimatedTimes.sorted(), estimatedUs.map { it * 1_000L }.sorted())
        var derivedOpens = 0
        val derived = DerivedDayFiles { _, epochDay, _, _ ->
            assertEquals(day, epochDay)
            derivedOpens++
            MemoryRangeSource(derivedBytes, "estimated/$includeRealRows")
        }
        val tiers = ArchiveHistoryTiers(store, index, clockAnchors = clockAnchors, templates = CoreS3Files)
        val rawReader = HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors, templates = CoreS3Files)
        val derivedReader = HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors, derived = derived, templates = CoreS3Files)
        val windows = listOf(
            HistoryWindow(day * 86_400, (day + 1) * 86_400, TimeZone.UTC),
            HistoryWindow(startS + 125, startS + 425, TimeZone.UTC),
        )
        for (window in windows) {
            val fromNs = window.fromS * 1_000_000_000L
            val toNs = window.toS * 1_000_000_000L
            val expectedEstimated = estimatedTimes.count { it in fromNs until toNs }
            val expectedReal = if (includeRealRows) estimatedTimes.count { it + 5_000_000_000L in fromNs until toNs } else 0
            for (metric in listOf(AirMetrics.PM25, AirMetrics.BOARD_TEMP)) {
                val raw = rawReader.observe(thing, metric, HistoryRange.DAY, window).first()
                store.reads = 0
                val fromDerived = derivedReader.observe(thing, metric, HistoryRange.DAY, window).first()
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
                val estimatedStarts = estimatedUs.map { (it * 1_000L).floorDiv(1_000_000_000L) }.filter { it >= window.fromS && it < window.toS }
                assertEquals(estimatedStarts.sorted(), fromDerived.points.filter { it.estimated }.map { it.startS }.sorted())
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
        val thing = "duplicate-thing"
        val store = Store()
        val index = Index()
        fun put(name: String, hours: LongArray, temperatures: FloatArray) {
            val sink = MemorySink()
            val columns = listOf(
                ParquetWriter.Column(UtcColumns.EVENT_NS, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column("boot_id_hi", ParquetType.INT64),
                ParquetWriter.Column("boot_id_lo", ParquetType.INT64),
                ParquetWriter.Column(ParquetIndexer.SEQ, ParquetType.INT64),
                ParquetWriter.Column(AirMetrics.BOARD_TEMP.column, ParquetType.FLOAT),
            )
            val writer = ParquetWriter(sink, columns, keyValueMetadata = mapOf(OpenSensorFooter.KEY to opensensorFooterJson(dict = null, desc = null, intervalMs = null, thing = thing)))
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
            val record = ArchiveRecord(thing, name, bytes.size.toLong(), 1, "test", 1, "fake", indexedAtEpochMs = 1, rows = summary.rows, utcRows = summary.utcRows, firstUtcNs = summary.firstUtcNs, lastUtcNs = summary.lastUtcNs)
            store.files[record.key] = bytes
            index.rows.value = index.rows.value + record
        }
        // Timeline order preserves this insertion for equal first UTC. The builder uses a.parquet,
        // whose duplicate sequence 1 is earlier and has a different value.
        put("z.parquet", longArrayOf(1, 3), floatArrayOf(90f, 99f))
        put("a.parquet", longArrayOf(1, 2), floatArrayOf(10f, 20f))
        val sources = index.rows.value.map { record -> DailyParquetSource(record) { ParquetFile.wrap(store.files.getValue(record.key)) } }
        val legacy = LegacyAggregator(thing)
        for (source in sources) source.open().use { legacy.add(it) }
        assertEquals(94.5f, legacy.buckets().first { it.resolution == Resolution.DAY }.metrics.getValue(AirMetrics.BOARD_TEMP.id).mean)
        val sink = MemorySink()
        val pass = DayPass.run(thing, day, sources, sink, nowEpochS = 0, templates = CoreS3Files)
        assertEquals(15f, pass.buckets.first { it.resolution == Resolution.DAY }.metrics.getValue(AirMetrics.BOARD_TEMP.id).mean)
        val derived = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(sink.toByteArray(), "duplicates") }
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val rawReader = HistorySeriesReader(store, index, tiers, templates = CoreS3Files)
        val derivedReader = HistorySeriesReader(store, index, tiers, derived = derived, templates = CoreS3Files)
        val windows = listOf(
            HistoryWindow(day * 86_400, (day + 1) * 86_400, TimeZone.UTC),
            HistoryWindow(day * 86_400 + 9_000, day * 86_400 + 12_600, TimeZone.UTC),
        )
        for (window in windows) {
            val raw = rawReader.observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, window).first()
            val fromDerived = derivedReader.observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, window).first()
            assertEquals(raw, fromDerived)
        }
        assertEquals(15f, rawReader.observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, windows.first()).first().mean)
        assertTrue(rawReader.observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, windows.last()).first().points.isEmpty())
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        var reads = 0
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            reads++
            return ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
        }
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.filter { thing == null || it.thing == thing }
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
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    /**
     * The 180 row board file with its UTC spread over 36 hours from 06:00 of [day], rows before
     * [nullBefore] without UTC. It gains the CoreS3 `opensensor` footer, or its day file would carry
     * no digest and give way to the raw files.
     */
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
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }, keyValueMetadata = raw.metadata.keyValueMetadata + coreS3Footer())
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    @Test
    fun theDerivedDayGivesTheRawPointsWithoutOpeningARawFile() = runTest {
        val first = respread()
        val thing = ParquetFile.wrap(first).metadata.footer?.thingId ?: "derived-thing"
        val store = Store()
        val index = Index()
        for ((i, bytes) in listOf(first, respread(nullBefore = 70)).withIndex()) {
            val r = ArchiveRecord(thing, "thing=$thing/day/part-$i.parquet", bytes.size.toLong(), i.toLong(), "s$i", 1, "fake")
            store.files[r.key] = bytes
            index.put(r)
        }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        var opened = 0
        val derived = DerivedDayFiles { s, epochDay, sources, anchors ->
            val sink = MemorySink()
            val pass = DayPass.run(s, epochDay, sources.map { r -> DailyParquetSource(r) { ParquetFile.wrap(store.files.getValue(r.key), r.name) } }, sink, anchors, (epochDay + 2) * 86_400, tiers = false, templates = CoreS3Files)
            pass.daily?.let {
                opened++
                MemoryRangeSource(sink.toByteArray(), "derived/$epochDay")
            }
        }
        val raw = HistorySeriesReader(store, index, tiers, templates = CoreS3Files)
        val fromDerived = HistorySeriesReader(store, index, tiers, derived = derived, templates = CoreS3Files)
        // Ends inside the second UTC day, so the window takes rows from both days and both sides of midnight.
        val ends = listOf((day + 1) * 86_400 + 9 * 3_600, (day + 1) * 86_400 + 18 * 3_600)
        for (zone in listOf(TimeZone.UTC, TimeZone.of("Asia/Kolkata"))) {
            for (end in ends) {
                val window = HistoryWindow.endingAt(end, HistoryRange.DAY, zone)
                for (metric in listOf(AirMetrics.PM25, AirMetrics.BOARD_TEMP)) {
                    val expected = raw.observe(thing, metric, HistoryRange.DAY, window).first()
                    store.reads = 0
                    val actual = fromDerived.observe(thing, metric, HistoryRange.DAY, window).first()
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
    fun theDerivedDayCorrectsPm25RowByRowFromItsHumidityColumn() = runTest {
        // #92. The daily file keeps raw CF=1 and RH. Reading it must correct each row, exactly as the
        // raw files do, and a row without a valid RH must give no corrected value.
        val thing = "humid-thing"
        val humidDay = HUMID_HOUR_S / 86_400
        val bytes = humidPm25Parquet(thing)
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(thing, "humid.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val sink = MemorySink()
        val pass = DayPass.run(thing, humidDay, index.rows.value.map { s -> DailyParquetSource(s) { ParquetFile.wrap(store.files.getValue(s.key)) } }, sink, emptyMap(), (humidDay + 2) * 86_400, tiers = false, templates = CoreS3Files)
        assertNotNull(pass.daily)
        val derivedBytes = sink.toByteArray()
        val derived = DerivedDayFiles { _, epochDay, _, _ -> if (epochDay == humidDay) MemoryRangeSource(derivedBytes, "humid") else null }
        val window = HistoryWindow(humidDay * 86_400, (humidDay + 1) * 86_400, TimeZone.UTC)
        val rawReader = HistorySeriesReader(store, index, tiers, templates = CoreS3Files)
        val derivedReader = HistorySeriesReader(store, index, tiers, derived = derived, templates = CoreS3Files)
        val first = EpaPm25Correction.correct(10.0, 50.0)!!.toFloat()
        val second = EpaPm25Correction.correct(250.0, 80.0)!!.toFloat()
        store.reads = 0
        val corrected = derivedReader.observe(thing, AirMetrics.PM25_CORRECTED, HistoryRange.DAY, window).first()
        assertEquals(0, store.reads, "the corrected day comes from the daily file")
        assertEquals(2L, corrected.validRows)
        assertEquals(listOf(first, second), corrected.points.mapNotNull { it.mean })
        assertEquals((first + second) / 2, corrected.mean!!, 1e-3f)
        assertEquals(rawReader.observe(thing, AirMetrics.PM25_CORRECTED, HistoryRange.DAY, window).first(), corrected)
        // The raw view of the same daily file stays the stored atmospheric value.
        val raw = derivedReader.observe(thing, AirMetrics.PM25, HistoryRange.DAY, window).first()
        assertEquals(listOf(9f, 170f, 30f, 31f), raw.points.mapNotNull { it.mean })
    }

    @Test
    fun aRawMicrosDayGivesTheSamePointsAsItsNanosOriginal() = runTest {
        val nanos = respread()
        val micros = microsLayout(nanos)
        val thing = ParquetFile.wrap(nanos).metadata.footer?.thingId ?: "derived-thing"
        assertEquals(ParquetLogicalType.TimeUnit.MICROS, ParquetFile.wrap(micros).leaf(UtcColumns.EVENT_US).timestampUtcUnit)
        suspend fun reader(bytes: ByteArray): HistorySeriesReader {
            val store = Store()
            val index = Index()
            val r = ArchiveRecord(thing, "thing=$thing/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
            store.files[r.key] = bytes
            index.put(r)
            ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
            return HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index), templates = CoreS3Files)
        }
        val old = reader(nanos)
        val new = reader(micros)
        // Both sides of midnight, so both UTC days of the file are read raw.
        val window = HistoryWindow(day * 86_400 + 9 * 3_600, (day + 1) * 86_400 + 9 * 3_600, TimeZone.UTC)
        for (metric in listOf(AirMetrics.PM25, AirMetrics.BOARD_TEMP)) {
            val expected = old.observe(thing, metric, HistoryRange.DAY, window).first()
            val actual = new.observe(thing, metric, HistoryRange.DAY, window).first()
            assertTrue(expected.points.isNotEmpty())
            assertEquals(expected.points, actual.points, "$metric")
            assertEquals(expected, actual)
        }
    }

    @Test
    fun aDayWithoutADerivedFileReadsTheRawFiles() = runTest {
        val bytes = respread()
        val thing = ParquetFile.wrap(bytes).metadata.footer?.thingId ?: "derived-thing"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(thing, "thing=$thing/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val window = HistoryWindow.endingAt((day + 1) * 86_400 + 9 * 3_600, HistoryRange.DAY, TimeZone.UTC)
        val expected = HistorySeriesReader(store, index, tiers, templates = CoreS3Files).observe(thing, AirMetrics.PM25, HistoryRange.DAY, window).first()
        val broken = DerivedDayFiles { _, _, _, _ -> MemoryRangeSource(ByteArray(64), "broken") }
        val actual = HistorySeriesReader(store, index, tiers, derived = broken, templates = CoreS3Files).observe(thing, AirMetrics.PM25, HistoryRange.DAY, window).first()
        assertEquals(expected.points, actual.points)
        assertTrue(actual.points.isNotEmpty())

        // A close failure happens after rows were extracted. The raw fallback must still see all
        // their identities instead of dropping keys left behind by the failed cache read.
        var failedCloses = 0
        val failsAfterReading = DerivedDayFiles { s, epochDay, sources, anchors ->
            val sink = MemorySink()
            val pass = DayPass.run(s, epochDay, sources.map { source -> DailyParquetSource(source) { ParquetFile.wrap(store.files.getValue(source.key)) } }, sink, anchors, 0, tiers = false, templates = CoreS3Files)
            pass.daily?.let {
                object : RangeSource by MemoryRangeSource(sink.toByteArray(), "close-failure/$epochDay") {
                    override fun close() {
                        failedCloses++
                        error("cache close failed")
                    }
                }
            }
        }
        val recovered = HistorySeriesReader(store, index, tiers, derived = failsAfterReading, templates = CoreS3Files).observe(thing, AirMetrics.PM25, HistoryRange.DAY, window).first()
        assertTrue(failedCloses > 0)
        assertEquals(expected, recovered)
    }

    @Test
    fun aSecondReadOfADerivedDayReusesItsFooter() = runTest {
        val bytes = respread()
        val thing = ParquetFile.wrap(bytes).metadata.footer?.thingId ?: "derived-thing"
        val store = Store()
        val index = Index()
        val r = ArchiveRecord(thing, "thing=$thing/day/part.parquet", bytes.size.toLong(), 1, "s", 1, "fake")
        store.files[r.key] = bytes
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val days = HashMap<Long, ByteArray>()
        for (epochDay in listOf(day, day + 1)) {
            val sink = MemorySink()
            DayPass.run(thing, epochDay, index.rows.value.map { s -> DailyParquetSource(s) { ParquetFile.wrap(store.files.getValue(s.key)) } }, sink, emptyMap(), 0, tiers = false, templates = CoreS3Files)
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
        val reader = HistorySeriesReader(store, index, tiers, derived = derived, footers = footers, templates = CoreS3Files)
        store.reads = 0
        val expected = reader.observe(thing, AirMetrics.PM25, HistoryRange.DAY, window).first()
        assertTrue(expected.points.isNotEmpty())
        assertEquals(2, footers.size)

        corrupt = true
        store.reads = 0
        val cold = HistorySeriesReader(store, index, tiers, derived = derived, templates = CoreS3Files).observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, window).first()
        assertTrue(store.reads > 0, "without the footer the damaged tail falls back to the raw file")
        store.reads = 0
        val warm = reader.observe(thing, AirMetrics.BOARD_TEMP, HistoryRange.DAY, window).first()
        assertEquals(0, store.reads, "the cached footer serves the second read")
        assertEquals(cold, warm)
        assertEquals(2, footers.size)
    }
}
