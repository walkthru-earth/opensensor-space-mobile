package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #63. The single [DayPass] gives the same tier buckets and the same derived day rows as the two
 * passes it replaced ([LegacyAggregator] and [LegacyDailyParquetBuilder]), on every real device and
 * contract fixture, each synced twice so the de-duplication runs, with unsynced files dated by an anchor.
 */
class HistoryGoldenTest {
    private val corpus = listOf(
        "device/v1-lz4-1row-unsynced.parquet",
        "device/v1-lz4-2rows-dated.parquet",
        "device/v1-lz4-90rows-dated.parquet",
        "device/v1-none-1row-dated.parquet",
        "device/v1-none-64rows-unsynced.parquet",
        "device/v1-none-90rows-dated.parquet",
        "device/v2-lz4-1row-dated.parquet",
        "device/v2-lz4-4rows-dated.parquet",
        "device/v2-none-90rows-unsynced.parquet",
        "device/v3-board-2rg-105rows.parquet",
        "device/v3-board-2rg-180rows.parquet",
        "v3/v3-contract-lz4-anchored.parquet",
        "v3/v3-contract-lz4-unsynced.parquet",
        "v3/v3-contract-none-anchored.parquet",
        "v3/v3-contract-none-unsynced.parquet",
        "v3/v3-hourly-lz4.parquet",
        "v3/v3-hourly-none.parquet",
        "v3/v3-max-groups-lz4.parquet",
        "v3/v3-max-groups-none.parquet",
        "v3/v3-one-lz4.parquet",
        "v3/v3-one-none.parquet",
    )

    /**
     * The 180 row board file with its UTC spread over 36 hours from 06:00 of [epochDay], so it crosses
     * midnight. With [nullBefore] the first rows lose their UTC, a later sync of the same rows.
     */
    private fun respread(epochDay: Long, nullBefore: Int = 0): ByteArray {
        val raw = ParquetFile.wrap(fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet"))
        val values = raw.leaves.map { leaf ->
            val col = raw.readColumn(leaf)
            if (leaf.name != ParquetIndexer.UTC) {
                col
            } else {
                val step = 36 * 3_600L / col.size
                ColumnValues.Int64(LongArray(col.size) { i -> (epochDay * 86_400L + 6 * 3_600L + i * step) * 1_000_000_000L }, BooleanArray(col.size) { it >= nullBefore })
            }
        }
        val sink = MemorySink()
        val writer = ParquetWriter(sink, raw.leaves.map { ParquetWriter.Column(it.name, it.type, it.isTimestampNanosUtc) }, keyValueMetadata = raw.metadata.keyValueMetadata)
        writer.begin()
        writer.rowGroup(values)
        writer.finish()
        return sink.toByteArray()
    }

    private class Entry(val record: ArchiveRecord, val bytes: ByteArray) {
        fun source() = DailyParquetSource(record) { ParquetFile.wrap(bytes, record.name) }
    }

    /** Every fixture, indexed, by station. Unsynced files get one anchor per boot, a copy of each file is synced again. */
    private fun archive(): Pair<Map<String, List<Entry>>, Map<String, TimeEstimate.Anchor>> {
        val anchors = HashMap<String, TimeEstimate.Anchor>()
        val synthetic = listOf("synthetic/midnight.parquet" to respread(20_000), "synthetic/midnight-later-sync.parquet" to respread(20_000, nullBefore = 70))
        val entries = (corpus.map { it to fixtureBytes("parquet/$it") } + synthetic).mapIndexed { n, (path, bytes) ->
            val file = ParquetFile.wrap(bytes)
            val index = ParquetIndexer.index(file)
            val station = file.metadata.stationId ?: "golden-station"
            var record = ArchiveRecord(
                station, "golden/$n/${path.substringAfterLast('/')}", bytes.size.toLong(), n.toLong(), "sha-$n", 1, "fixture",
                indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = index.utcRows,
                firstUtcNs = index.firstUtcNs, lastUtcNs = index.lastUtcNs, firstMonoUs = index.firstMonoUs, lastMonoUs = index.lastMonoUs,
                firstSeq = index.firstSeq, lastSeq = index.lastSeq,
            )
            val boot = index.bootId
            if (!index.hasUtc && boot != null && index.firstMonoUs != null && index.lastMonoUs != null) {
                val anchor = anchors.getOrPut(boot) { TimeEstimate.Anchor(boot, 1_788_912_000_123_456_000L + n * 3_600_000_000_000L, index.lastMonoUs!!, "clock:golden-$n") }
                record = record.copy(estFirstUtcNs = anchor.estimateNsOrNull(index.firstMonoUs!!), estLastUtcNs = anchor.estimateNsOrNull(index.lastMonoUs!!), estAnchorFile = anchor.sourceFile)
            }
            Entry(record, bytes)
        }
        val copies = entries.map { Entry(it.record.copy(name = it.record.name.replace("golden/", "golden-copy/")), it.bytes) }
        return (entries + copies).groupBy { it.record.station } to anchors
    }

    @Test
    fun theSinglePassGivesTheSameTiersAndDailyRowsAsTheTwoPasses() {
        val (stations, anchors) = archive()
        var days = 0
        var rows = 0
        for ((station, entries) in stations) {
            for (day in StationDays.of(entries.map { it.record })[station].orEmpty().sorted()) {
                val from = day * StationDays.DAY_NS
                val dayEntries = entries.filter { it.record.onTimeline(from, from + StationDays.DAY_NS) }
                // The daily builder's order, which the pass uses for both outputs.
                val ordered = dayEntries.sortedWith(compareBy({ it.record.firstUtcNs ?: Long.MAX_VALUE }, { it.record.name }))
                val now = (day + 2) * 86_400

                val legacy = LegacyAggregator(station)
                for (e in ordered) ParquetFile.wrap(e.bytes).use { legacy.add(it, if (e.record.hasEstimatedUtc) anchors[e.record.bootId] else null) }
                val legacyBuckets = legacy.buckets().filter { it.startEpochS >= day * 86_400 && it.startEpochS < (day + 1) * 86_400 }
                val legacySink = MemorySink()
                val legacyDaily = LegacyDailyParquetBuilder.build(station, day, dayEntries.map { it.source() }, legacySink, anchors, now)

                val sink = MemorySink()
                val pass = DayPass.run(station, day, dayEntries.map { it.source() }, sink, anchors, now)
                assertEquals(legacyBuckets, pass.buckets, "tiers of $station day $day")
                assertEquals(emptyList(), pass.failedSources)
                if (legacyDaily == null) {
                    assertEquals(null, pass.daily, "no daily file on $station day $day")
                    continue
                }
                val daily = assertNotNull(pass.daily)
                assertEquals(legacyDaily.rows, daily.rows)
                assertEquals(legacyDaily.columns, daily.columns)
                assertEquals(legacyDaily.rowGroups, daily.rowGroups)
                assertEquals(legacyDaily.sources, daily.sources)
                assertSameRows(ParquetFile.wrap(legacySink.toByteArray()), ParquetFile.wrap(sink.toByteArray()))
                days++
                rows += daily.rows
            }
        }
        assertTrue(days >= 6, "the corpus covers $days station-days")
        assertTrue(rows > 500, "the corpus covers $rows rows")
    }

    @Test
    fun aTiersOnlyPassGivesTheSameTiersWithoutADailyFile() {
        val (stations, anchors) = archive()
        val (station, entries) = stations.entries.first { (_, list) -> list.any { it.record.hasUtc && it.record.rows!! > 100 } }
        val day = StationDays.of(entries.map { it.record })[station]!!.first()
        val withDaily = DayPass.run(station, day, entries.map { it.source() }, MemorySink(), anchors, 0)
        val tiersOnly = DayPass.run(station, day, entries.map { it.source() }, null, anchors, 0)
        assertEquals(withDaily.buckets, tiersOnly.buckets)
        assertEquals(null, tiersOnly.daily)
    }

    private fun assertSameRows(expected: ParquetFile, actual: ParquetFile) {
        assertEquals(expected.numRows, actual.numRows)
        assertEquals(expected.leaves.map { it.name to it.type }, actual.leaves.map { it.name to it.type })
        assertEquals(expected.metadata.keyValueMetadata, actual.metadata.keyValueMetadata)
        for (leaf in expected.leaves) {
            val e = expected.readColumn(leaf)
            val a = actual.readColumn(actual.leaf(leaf.name))
            for (i in 0 until e.size) {
                assertEquals(e.isNull(i), a.isNull(i), "${leaf.name} row $i null")
                if (e.isNull(i)) continue
                when (e) {
                    is ColumnValues.Binary -> assertContentEquals(e.bytes(i), (a as ColumnValues.Binary).bytes(i), "${leaf.name} row $i")
                    is ColumnValues.Float32 -> assertEquals(e.values[i].toRawBits(), (a as ColumnValues.Float32).values[i].toRawBits(), "${leaf.name} row $i")
                    is ColumnValues.Float64 -> assertEquals(e.values[i].toRawBits(), (a as ColumnValues.Float64).values[i].toRawBits(), "${leaf.name} row $i")
                    else -> assertEquals(e.asLong(i), a.asLong(i), "${leaf.name} row $i")
                }
            }
        }
    }
}
