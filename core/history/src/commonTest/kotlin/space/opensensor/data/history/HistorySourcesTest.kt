package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetWriter
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The shared History sources over a fake archive: index repair, the row reader and the in-memory tiers. */
class HistorySourcesTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dayStart = 1_789_603_200L // 2026-09-17T00:00Z
    private val board = fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")
    private fun window(range: HistoryRange, toS: Long) = HistoryWindow.endingAt(toS, range, TimeZone.UTC)

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files

        /** Runs once, on the first read, while repair holds a snapshot of the record. */
        var onFirstRead: (() -> Unit)? = null

        /** Thrown by every later open, or by every later close, to stand in for a failing file. */
        var openFailure: Throwable? = null
        var closeFailure: Throwable? = null
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            onFirstRead?.also { onFirstRead = null }?.invoke()
            openFailure?.let { throw it }
            val bytes = ByteArrayReader(files[key] ?: throw NoSuchElementException(key.name))
            val failure = closeFailure ?: return bytes
            return object : ByteReader by bytes {
                override fun close() = throw failure
            }
        }
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.filter { thing == null || it.thing == thing }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())

        /** Runs once, in the time-estimate step, after the pass found nothing left to index. */
        var onEstimate: (() -> Unit)? = null
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
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> {
            onEstimate?.also { onEstimate = null }?.invoke()
            return rows.value.filter { it.thing == thing && it.isIndexed }
        }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    private fun record(name: String, crc: Long = 1, owner: String = thing) =
        ArchiveRecord(thing = owner, name = name, bytes = board.size.toLong(), crc32 = crc, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake")

    private suspend fun archive(vararg names: String): Triple<Store, Index, ArchiveIndexRepair> {
        val store = Store()
        val index = Index()
        for ((i, name) in names.withIndex()) {
            val r = record(name, crc = i.toLong())
            store.files[r.key] = board
            index.put(r)
        }
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files)
        repair.backfillAll()
        return Triple(store, index, repair)
    }

    @Test
    fun aFooterAnchorIsIgnoredAndAReceiptRecoversBothHistoryReadersWithoutTimedRows() = runTest {
        val boot = "e452563e96c72ca057a48924550779b6"
        val raw = ParquetFile.wrap(fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet"))
        val source = ParquetIndexer.index(raw, templates = CoreS3Files)
        val utc = 1_788_912_000_123_456_000L
        val parts = ArrayList<ByteArray>()
        val sink = object : ParquetSink { override fun write(bytes: ByteArray) { parts += bytes.copyOf() } }
        // Old firmware wrote a clock anchor into the footer. Decision 25 drops those keys, so repair must not read them.
        val metadata = raw.metadata.keyValueMetadata + LegacyFooterKeys.anchor(monoUs = source.lastMonoUs!!, utcNs = utc, source = 3)
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }, keyValueMetadata = metadata)
        writer.begin()
        writer.rowGroup(raw.leaves.map { raw.readColumn(it) })
        writer.finish()
        val bytes = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(bytes, offset); offset += part.size }
        val store = Store()
        val index = Index()
        val record = record("station=$thing/unsynced/boot=$boot/raw.parquet")
        store.files[record.key] = bytes
        index.put(record)
        val saved = ArrayList<ClockAnchor>()
        val receipts = object : ClockAnchorStore {
            override suspend fun anchors(thing: String) = saved.filter { it.thing == thing }
            override suspend fun put(anchor: ClockAnchor) { if (anchor !in saved) saved += anchor }
        }
        val repair = ArchiveIndexRepair(store, index, clockAnchors = receipts, templates = CoreS3Files)
        repair.backfillAll()
        assertEquals(null, index.get(record.key)!!.estLastUtcNs)
        assertTrue(saved.isEmpty())
        // A sync receipt is the anchor now.
        saved += ClockAnchor(thing, boot, utc, source.lastMonoUs!!, source = "3")
        assertEquals(1, repair.estimateTimes())
        val estimated = index.get(record.key)!!
        assertEquals(0L, estimated.utcRows)
        assertEquals(utc, estimated.estLastUtcNs)
        assertEquals(2, ThingDays.of(listOf(estimated))[thing]!!.size)
        val tiers = ArchiveHistoryTiers(store, index, clockAnchors = receipts, templates = CoreS3Files)
        val reader = HistorySeriesReader(store, index, tiers, clockAnchors = receipts, templates = CoreS3Files)
        val midnight = utc / 1_000_000_000L
        val previousDay = reader.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, midnight)).first()
        assertTrue(previousDay.estimatedRows > 0)
        assertTrue(tiers.observeDays(thing).first().count > 0)
        assertEquals(0, ArchiveIndexRepair(store, index, clockAnchors = receipts, templates = CoreS3Files).estimateTimes())
        assertTrue(store.files[record.key]!!.contentEquals(bytes))
    }

    @Test
    fun repairIndexesEveryVerifiedFileAndMarksForeignAndMissingOnes() = runTest {
        val own = "station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        val foreign = "station=other/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        val missing = "station=$thing/year=2026/month=09/day=17/data_1000.parquet"
        val store = Store()
        val index = Index()
        store.files[record(own).key] = board
        store.files[record(foreign).key] = board
        index.put(record(own))
        index.put(record(foreign))
        index.put(record(missing))
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files)

        assertEquals(3, repair.backfill())
        val indexed = index.get(record(own).key)!!
        assertTrue(indexed.isIndexed)
        assertEquals(105L, indexed.rows)
        assertEquals(105L, indexed.utcRows)
        assertTrue(index.get(record(foreign).key)!!.indexError!!.contains("other"))
        assertEquals("file missing from archive", index.get(record(missing).key)!!.indexError)
        // A failed retry of an already failed record writes nothing, so the loop ends.
        assertEquals(0, repair.backfill())
    }

    /** A file the engine saves late in a pass, after its last batch, which only a later pass indexes. */
    private fun putDuringRepair(store: Store, index: Index): ArchiveRecord {
        val late = record("station=$thing/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet", crc = 9)
        index.onEstimate = {
            store.files[late.key] = board
            index.rows.value = index.rows.value + late
        }
        return late
    }

    @Test
    fun aPutDuringARepairPassIsIndexedBeforeItSettles() = runTest {
        val store = Store()
        val index = Index()
        val first = record("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        store.files[first.key] = board
        index.put(first)
        val late = putDuringRepair(store, index)
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files)
        repair.backfillUntilSettled()
        assertTrue(index.get(first.key)!!.isIndexed)
        assertTrue(index.get(late.key)!!.isIndexed)
    }

    @Test
    fun repairKeepsAFingerprintTheEngineFilledDuringThePass() = runTest {
        val store = Store()
        val index = Index()
        val old = record("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet").copy(sha256 = "")
        store.files[old.key] = board
        index.put(old)
        // The engine fills the fingerprint of an older receipt while repair reads the file.
        store.onFirstRead = { index.rows.value = index.rows.value.map { if (it.key == old.key) it.copy(sha256 = "filled") else it } }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val after = index.get(old.key)!!
        assertEquals("filled", after.sha256)
        assertTrue(after.isIndexed)
        assertEquals(105L, after.rows)
    }

    @Test
    fun theWatcherIndexesAPutThatLandsDuringItsPass() = runTest {
        val store = Store()
        val index = Index()
        val repair = ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files)
        backgroundScope.launch { HistoryOrchestrator(repair, index).run {} }
        val late = putDuringRepair(store, index)
        val first = record("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        store.files[first.key] = board
        index.put(first)
        val all = withContext(Dispatchers.Default) {
            withTimeout(10_000) { index.rows.first { rows -> rows.size == 2 && rows.all { it.indexedAtEpochMs != null } } }
        }
        assertTrue(all.all { it.isIndexed })
        assertTrue(index.get(late.key)!!.isIndexed)
    }

    @Test
    fun dayViewReadsTheRowsOnce() = runTest {
        val (store, index) = archive(
            "station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet",
            "station=$thing/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet",
        )
        val reader = HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index), templates = CoreS3Files)
        val day = reader.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertFalse(day.binned)
        // The two copies share boot and sequence, so each row counts once.
        assertEquals(105, day.points.size)
        assertEquals(105L, day.validRows)
        assertEquals(27.93f, day.mean!!, 0.01f)
        assertEquals(105f / 8640f, day.coverage, 1e-6f)

        val empty = reader.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart)).first()
        assertTrue(empty.isEmpty)
    }

    @Test
    fun corrected_pm25_is_read_row_by_row_and_falls_back_to_nothing_without_rh() = runTest {
        // #92. The Day view and the tiers both correct each row, then average.
        val store = Store()
        val index = Index()
        val humid = humidPm25Parquet()
        val r = ArchiveRecord(thing = thing, name = "station=$thing/year=2026/month=09/day=17/humid.parquet", bytes = humid.size.toLong(), crc32 = 7, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake")
        store.files[r.key] = humid
        index.put(r)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val reader = HistorySeriesReader(store, index, tiers, templates = CoreS3Files)
        val expected = ((EpaPm25Correction.correct(10.0, 50.0)!! + EpaPm25Correction.correct(250.0, 80.0)!!) / 2).toFloat()
        val day = reader.observe(thing, Metrics.PM25_CORRECTED, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(2L, day.validRows)
        assertEquals(expected, day.mean!!, 1e-3f)
        val raw = reader.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(4L, raw.validRows)
        assertEquals(60f, raw.mean!!, 1e-3f)
        val week = reader.observe(thing, Metrics.PM25_CORRECTED, HistoryRange.WEEK, window(HistoryRange.WEEK, dayStart + 86_400)).first()
        assertEquals(expected, week.mean!!, 1e-3f)
        assertTrue(Metrics.PM25_CORRECTED.id in tiers.observeMetricsWithData(thing).first())

        // A real CoreS3 file. Its RH column is all null, so it has no corrected PM2.5 at all and the cards show raw.
        val (boardStore, boardIndex) = archive("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        val boardTiers = ArchiveHistoryTiers(boardStore, boardIndex, templates = CoreS3Files)
        val none = HistorySeriesReader(boardStore, boardIndex, boardTiers, templates = CoreS3Files)
            .observe(thing, Metrics.PM25_CORRECTED, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(0L, none.validRows)
        assertFalse(Metrics.PM25_CORRECTED.id in boardTiers.observeMetricsWithData(thing).first())
    }

    @Test
    fun coverageOfAWindowThatContainsNowCountsOnlyElapsedTime() = runTest {
        val (store, index) = archive("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val noon = dayStart + 43_200
        val reader = HistorySeriesReader(store, index, tiers, nowEpochS = { noon }, templates = CoreS3Files)

        // The day ends at midnight, but only the twelve hours before noon could have readings.
        val day = reader.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(105L, day.validRows)
        assertEquals(105f / 4320f, day.coverage, 1e-6f)

        // The hourly Today path and the tier windows use the same denominator.
        val today = reader.observeHourlyDay(thing, Metrics.PM25, window(HistoryRange.DAY, dayStart + 86_400)).first()
        assertEquals(today.validRows.toFloat() / 4320f, today.coverage, 1e-6f)
        val week = reader.observe(thing, Metrics.PM25, HistoryRange.WEEK, window(HistoryRange.WEEK, dayStart + 86_400)).first()
        assertEquals(week.validRows.toFloat() / ((noon - week.fromS) / 10), week.coverage, 1e-6f)

        // A past window keeps its whole span, and a window not yet started covers nothing.
        val past = HistorySeriesReader(store, index, tiers, nowEpochS = { dayStart + 10 * 86_400 }, templates = CoreS3Files)
        assertEquals(105f / 8640f, past.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first().coverage, 1e-6f)
        val before = HistorySeriesReader(store, index, tiers, nowEpochS = { dayStart - 1 }, templates = CoreS3Files)
        assertEquals(0f, before.observe(thing, Metrics.PM25, HistoryRange.DAY, window(HistoryRange.DAY, dayStart + 86_400)).first().coverage)
    }

    @Test
    fun weekViewReadsTheInMemoryTiers() = runTest {
        val (store, index) = archive("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val reader = HistorySeriesReader(store, index, tiers, templates = CoreS3Files)
        val week = reader.observe(thing, Metrics.PM25, HistoryRange.WEEK, window(HistoryRange.WEEK, dayStart + 86_400)).first()
        assertEquals(2, week.points.size)
        assertEquals(27.93f, week.mean!!, 0.01f)
        assertTrue(week.points.all { it.min != null && it.max != null })
        assertEquals(15f / 90f, week.points[0].coverage, 1e-6f)
        assertEquals(1f, week.points[1].coverage, 1e-6f)

        assertEquals(DaySpan(1, dayStart, dayStart), tiers.observeDays(thing).first())
        val metrics = tiers.observeMetricsWithData(thing).first()
        assertTrue(Metrics.PM25.id in metrics)
        assertTrue(Metrics.BOARD_TEMP.id in metrics)
        assertEquals(DaySpan(), tiers.observeDays("nobody").first())
    }

    @Test
    fun tiersFollowTheIndex() = runTest {
        val store = Store()
        val index = Index()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        assertEquals(0, tiers.observeDays(thing).first().count)
        val name = "station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
        store.files[record(name).key] = board
        index.put(record(name))
        // Not indexed yet, so not on the timeline.
        assertEquals(0, tiers.observeDays(thing).first().count)
        ArchiveIndexRepair(store, index, templates = CoreS3Files).backfillAll()
        assertEquals(1, tiers.observeDays(thing).first().count)
    }

    @Test
    fun signatureIgnoresOrderAndCarriesTheEstimate() {
        val a = record("a", 1)
        val b = record("b", 2)
        assertEquals(ArchiveHistoryTiers.signature(listOf(a, b)), ArchiveHistoryTiers.signature(listOf(b, a)))
        val est = b.copy(indexedAtEpochMs = 1, rows = 1, utcRows = 0, estFirstUtcNs = 5, estLastUtcNs = 6, estAnchorFile = "a")
        assertTrue(ArchiveHistoryTiers.signature(listOf(a, b)) != ArchiveHistoryTiers.signature(listOf(a, est)))
    }

    /**
     * #157. A copy of `v1-none-90rows-dated` with its first PM2.5 page header byte changed, as an SD
     * card can damage it before OPEN computes the CRC. Its footer, timestamps and PMS status still read,
     * so it verifies and indexes, only decoding PM2.5 throws.
     */
    private fun damagedPm25(): ByteArray {
        val bytes = fixtureBytes("parquet/device/v1-none-90rows-dated.parquet").copyOf()
        val chunk = ParquetFile.wrap(bytes).metadata.rowGroups[0].columns.first { it.name == Metrics.PM25.column }
        val at = chunk.firstPageOffset.toInt()
        bytes[at] = (bytes[at].toInt() xor 0xFF).toByte()
        return bytes
    }

    private suspend fun archiveOf(vararg files: Pair<String, ByteArray>): Pair<Store, Index> {
        val store = Store()
        val index = Index()
        for ((i, file) in files.withIndex()) {
            val r = ArchiveRecord(thing = thing, name = file.first, bytes = file.second.size.toLong(), crc32 = i.toLong(), sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake")
            store.files[r.key] = file.second
            index.put(r)
        }
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        return store to index
    }

    private val sept8 = 1_788_825_600L // 2026-09-08T00:00Z
    private val damagedName = "station=$thing/year=2026/month=09/day=08/data_1900_1491409bed013e1999dc4072ac91ab0f_80-169-6.parquet"
    private val healthyName = "station=$thing/year=2026/month=09/day=08/data_1945_e452563e96c72ca057a48924550779b6_92-181-11.parquet"
    private val healthy = fixtureBytes("parquet/device/v1-lz4-90rows-dated.parquet")

    private suspend fun day(store: Store, index: Index, metric: MetricSpec, from: Long = dayStart): HistorySeries =
        HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index), templates = CoreS3Files).observe(thing, metric, HistoryRange.DAY, window(HistoryRange.DAY, from + 86_400)).first()

    @Test
    fun aDamagedMetricPageSkipsOnlyItsFileAndTheRestStillCharts() = runTest {
        assertFailsWith<Exception> { ParquetFile.wrap(damagedPm25()).readColumn(Metrics.PM25.column) }
        val (alone, aloneIndex) = archiveOf(healthyName to healthy)
        val expected = day(alone, aloneIndex, Metrics.PM25, sept8)
        assertTrue(expected.validRows > 0)
        assertEquals(0, expected.unreadableSources)

        val (store, index) = archiveOf(damagedName to damagedPm25(), healthyName to healthy)
        assertTrue("the damaged copy still indexes", index.rows.value.all { it.isIndexed })
        val mixed = day(store, index, Metrics.PM25, sept8)
        assertEquals("the healthy file charts as if alone", expected.points, mixed.points)
        assertEquals(expected.validRows, mixed.validRows)
        assertEquals("the damaged file is counted, not fatal", 1, mixed.unreadableSources)

        // The damage is in one column only, so another metric of the same file still reads.
        val pm10 = day(store, index, Metrics.PM10, sept8)
        assertEquals(0, pm10.unreadableSources)
        assertTrue(pm10.validRows > day(alone, aloneIndex, Metrics.PM10, sept8).validRows)
    }

    @Test
    fun aFileThatFailsToCloseLeavesNoRowsBehind() = runTest {
        val (store, index) = archive("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        store.closeFailure = IllegalStateException("close failed")
        val failed = day(store, index, Metrics.PM25)
        // Every row decoded before close threw, none may chart from a source that did not read through.
        assertEquals(0L, failed.validRows)
        assertTrue(failed.isEmpty)
        assertEquals(1, failed.unreadableSources)

        store.closeFailure = null
        val ok = day(store, index, Metrics.PM25)
        assertEquals(105L, ok.validRows)
        assertEquals(0, ok.unreadableSources)
    }

    @Test
    fun cancellationWhileReadingARawFileStillPropagates() = runTest {
        val (store, index) = archive("station=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
        val reader = HistorySeriesReader(store, index, ArchiveHistoryTiers(store, index), templates = CoreS3Files)

        // Read the rows directly, a flow would end a cancelled read quietly and hide which way it went.
        suspend fun read() = reader.fromRows(thing, Metrics.PM25, HistoryRange.DAY, dayStart, dayStart + 86_400)
        store.closeFailure = CancellationException("closing")
        assertFailsWith<CancellationException> { read() }
        store.closeFailure = null
        store.openFailure = CancellationException("opening")
        assertFailsWith<CancellationException> { read() }
        store.openFailure = null
        assertEquals(105L, read().validRows)
    }

    @Test
    fun producerThingComesFromTheHivePath() {
        assertEquals("s1", ArchiveIndexRepair.producerThing("station=s1/year=2026/month=09/day=17/data.parquet"))
        assertEquals("s2", ArchiveIndexRepair.producerThing("station=s2/unsynced/boot=ab/data.parquet"))
        assertNull(ArchiveIndexRepair.producerThing("benchmarks/x.parquet"))
    }
}
