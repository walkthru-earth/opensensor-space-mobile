package space.opensensor.data.history

import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetFormatException
import space.opensensor.data.history.parquet.ParquetLogicalType.TimeUnit
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The UTC unit is read from the Parquet logical type, never from the column name alone. A board file
 * rewritten in the v6.12 / v2.6 layout (`*_utc_us`, MICROS, field ids) must index, summarise and
 * derive exactly like the older NANOS file it came from.
 */
class UtcColumnsTest {
    private val fixture = "parquet/device/v3-board-2rg-180rows.parquet"

    /**
     * The fixture in the current device layout. [seqOffset] makes the rows new to a day that already
     * holds the original file.
     */
    private fun current(seqOffset: Long = 0, idShift: Int = 0): ByteArray = microsLayout(fixtureBytes(fixture), seqOffset, idShift)

    private fun write(columns: List<ParquetWriter.Column>, values: List<ColumnValues>, meta: Map<String, String> = emptyMap()): ByteArray = writeParquet(columns, values, meta)

    private fun single(name: String, type: ParquetType, unit: TimeUnit?, values: ColumnValues): ParquetFile =
        ParquetFile.wrap(write(listOf(ParquetWriter.Column(name, type, timestampUtc = unit)), listOf(values)))

    @Test
    fun microsAndNanosFilesOfTheSameInstantsIndexAndSummariseTheSame() {
        val old = ParquetFile.wrap(fixtureBytes(fixture))
        val new = ParquetFile.wrap(current())
        assertEquals(TimeUnit.NANOS, old.leaf(UtcColumns.EVENT_NS).timestampUtcUnit)
        assertEquals(TimeUnit.MICROS, new.leaf(UtcColumns.EVENT_US).timestampUtcUnit)
        val oldIndex = ParquetIndexer.index(old, templates = CoreS3Files)
        val newIndex = ParquetIndexer.index(new, templates = CoreS3Files)
        assertTrue(oldIndex.utcRows > 0)
        // The fixture's instants are whole microseconds, so nothing is lost by the MICROS layout.
        fun ParquetIndex.layoutFree() = copy(columns = 0, rowGroups = 0, createdBy = null)
        assertEquals(oldIndex.layoutFree(), newIndex.layoutFree())
        assertEquals(FileSummary.of("a", old, templates = CoreS3Files), FileSummary.of("a", new, templates = CoreS3Files))
        assertEquals(UtcColumns.readEventNs(old)!!.values.toList(), UtcColumns.readEventNs(new)!!.values.toList())
    }

    @Test
    fun theUnitComesFromTheLogicalType() {
        val us = single(UtcColumns.EVENT_US, ParquetType.INT64, TimeUnit.MICROS, ColumnValues.Int64(longArrayOf(1_789_638_155_123_456L), null))
        assertEquals(1_789_638_155_123_456_000L, UtcColumns.readEventNs(us)!!.values[0])
        // An old name annotated MICROS still reads as micros, the name only finds the column.
        val mislabeled = single(UtcColumns.EVENT_NS, ParquetType.INT64, TimeUnit.MICROS, ColumnValues.Int64(longArrayOf(1_789_638_155_123_456L), null))
        assertEquals(1_789_638_155_123_456_000L, UtcColumns.readEventNs(mislabeled)!!.values[0])
    }

    @Test
    fun anUnannotatedOldNameReadsAsNanosButAnUnannotatedNewNameIsRefused() {
        val legacy = single(UtcColumns.EVENT_NS, ParquetType.INT64, null, ColumnValues.Int64(longArrayOf(1_789_638_155_123_456_789L), null))
        assertEquals(1_789_638_155_123_456_789L, UtcColumns.readEventNs(legacy)!!.values[0])
        val bare = single(UtcColumns.EVENT_US, ParquetType.INT64, null, ColumnValues.Int64(longArrayOf(1L), null))
        assertFailsWith<ParquetFormatException> { UtcColumns.readEventNs(bare) }
        val int32 = single(UtcColumns.EVENT_US, ParquetType.INT32, null, ColumnValues.Int32(intArrayOf(1), null))
        assertFailsWith<ParquetFormatException> { UtcColumns.readEventNs(int32) }
        assertNull(UtcColumns.readEventNs(single("sequence", ParquetType.INT64, null, ColumnValues.Int64(longArrayOf(1L), null))))
    }

    @Test
    fun everyUnitDropsTheSameOutOfRangeValues() {
        // One rule for every unit. A MICROS value whose nanoseconds would not fit a Long is null when it
        // becomes a derived file's micros too, not passed through, so the timeline and the derived file agree.
        val far = Long.MAX_VALUE / 10
        val us = single(UtcColumns.EVENT_US, ParquetType.INT64, TimeUnit.MICROS, ColumnValues.Int64(longArrayOf(1_789_638_155_000_000L, far), null))
        val leaf = us.leaf(UtcColumns.EVENT_US)
        val micros = UtcColumns.toMicros(us.readColumn(leaf), leaf)
        assertEquals(1_789_638_155_000_000L, micros.asLong(0))
        assertNull(micros.asLong(1))
        assertNull(UtcColumns.readNs(us, leaf).asLong(1))
        assertNull(UtcColumns.scaleToNs(far, 1_000L))
        assertEquals(false, UtcColumns.fitsNs(far, 1_000L))
        assertEquals(true, UtcColumns.fitsNs(far, 1L))
    }

    @Test
    fun aMicrosValuePast2262IsNullInsteadOfWrapping() {
        val values = ColumnValues.Int64(longArrayOf(1_789_638_155_000_000L, Long.MAX_VALUE / 10, 0L), booleanArrayOf(true, true, false))
        val ns = UtcColumns.toNs(values, 1_000L)
        assertEquals(1_789_638_155_000_000_000L, ns.asLong(0))
        assertNull(ns.asLong(1))
        assertNull(ns.asLong(2))
        assertEquals(3, ns.size)
    }

    @Test
    fun rangeEndsRoundTowardsTheirSide() {
        assertEquals(1L, UtcColumns.nsToUs(1_999L))
        assertEquals(2L, UtcColumns.nsToUsCeil(1_001L))
        assertEquals(2L, UtcColumns.nsToUsCeil(2_000L))
        assertEquals(-2L, UtcColumns.nsToUs(-1_001L))
        assertEquals(-1L, UtcColumns.nsToUsCeil(-1_001L))
        // Any unit, a millisecond range keeps its ends on their side too.
        assertEquals(1L, UtcColumns.nsToUnit(1_999_999L, 1_000_000L))
        assertEquals(2L, UtcColumns.nsToUnitCeil(1_000_001L, 1_000_000L))
        assertEquals(5L, UtcColumns.nsToUnit(5L, 1L))
        assertEquals(1_000L, UtcColumns.scaleToNs(1L, 1_000L))
        assertNull(UtcColumns.scaleToNs(Long.MAX_VALUE / 10, 1_000L))
    }

    @Test
    fun aCeilEndAtTheLongExtremesDoesNotWrap() {
        assertEquals(Long.MIN_VALUE.floorDiv(1_000L) + 1, UtcColumns.nsToUsCeil(Long.MIN_VALUE))
        assertTrue(UtcColumns.nsToUsCeil(Long.MIN_VALUE) < 0)
        assertEquals(Long.MIN_VALUE, UtcColumns.nsToUnitCeil(Long.MIN_VALUE, 1L))
        assertEquals(Long.MAX_VALUE / 1_000L + 1, UtcColumns.nsToUsCeil(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, UtcColumns.nsToUnitCeil(Long.MAX_VALUE, 1L))
    }

    @Test
    fun aDayOfOldNanosAndNewMicrosFilesIsWrittenInMicrosWithTheDeviceFieldIds() {
        val oldBytes = fixtureBytes(fixture)
        val newBytes = current(seqOffset = 1_000_000)
        val old = ParquetFile.wrap(oldBytes)
        val thing = old.metadata.footer?.thingId ?: "thing-a"
        val day = ThingDays.dayOf(ParquetIndexer.index(old, templates = CoreS3Files).firstUtcNs!!)
        fun source(name: String, bytes: ByteArray, n: Long): DailyParquetSource {
            val index = ParquetIndexer.index(ParquetFile.wrap(bytes), templates = CoreS3Files)
            val record = ArchiveRecord(
                thing, name, bytes.size.toLong(), n, "sha-$n", 1, "fixture",
                indexedAtEpochMs = 1, rows = index.rows, bootId = index.bootId, utcRows = index.utcRows,
                firstUtcNs = index.firstUtcNs, lastUtcNs = index.lastUtcNs, firstMonoUs = index.firstMonoUs, lastMonoUs = index.lastMonoUs,
                firstSeq = index.firstSeq, lastSeq = index.lastSeq,
            )
            return DailyParquetSource(record) { ParquetFile.wrap(bytes, name) }
        }
        val sink = MemorySink()
        val result = assertNotNull(DailyParquetBuilder.build(thing, day, listOf(source("old.parquet", oldBytes, 1), source("new.parquet", newBytes, 2)), sink, nowEpochS = 0))
        val daily = ParquetFile.wrap(sink.toByteArray())
        assertEquals(2, result.sources.count { it.rowsUsed > 0 })

        assertNull(daily.metadata.leaf(UtcColumns.EVENT_NS), "a derived file never holds the old name")
        val utc = daily.leaf(UtcColumns.EVENT_US)
        assertEquals(TimeUnit.MICROS, utc.timestampUtcUnit)
        val dayStartUs = day * ThingDays.DAY_NS / 1_000
        val micros = daily.readColumn(utc)
        for (i in 0 until micros.size) {
            val v = micros.asLong(i) ?: continue
            assertTrue(v >= dayStartUs && v < dayStartUs + 86_400_000_000L, "row $i is $v, not micros of day $day")
        }
        // Every device column takes the id of its position, the old file having none.
        val newLeaves = ParquetFile.wrap(newBytes).leaves.associate { it.name to it.fieldId }
        for (leaf in daily.leaves) {
            if (leaf.name == DailyParquetBuilder.SOURCE_INDEX || leaf.name == DailyParquetBuilder.ESTIMATED_UTC) {
                assertNull(leaf.fieldId, "${leaf.name} is added by the phone")
            } else {
                assertEquals(newLeaves[leaf.name], leaf.fieldId, leaf.name)
            }
        }

        // Two newer sources that disagree on an id: the id is dropped, the column kept.
        val other = current(seqOffset = 2_000_000, idShift = 100)
        val mixed = MemorySink()
        DailyParquetBuilder.build(thing, day, listOf(source("new.parquet", newBytes, 2), source("other.parquet", other, 3)), mixed, nowEpochS = 0)
        val conflicted = ParquetFile.wrap(mixed.toByteArray())
        assertTrue(conflicted.leaves.all { it.fieldId == null })
        assertEquals(TimeUnit.MICROS, conflicted.leaf(UtcColumns.EVENT_US).timestampUtcUnit)

        // One id under two names, as a rename in place would give: neither name keeps it.
        val renamed = current(seqOffset = 3_000_000).let { bytes ->
            val raw = ParquetFile.wrap(bytes)
            val last = raw.leaves.last().name
            val columns = raw.leaves.map { ParquetWriter.Column(if (it.name == last) "${last}_renamed" else it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) }
            write(columns, raw.leaves.map { raw.readColumn(it) }, raw.metadata.keyValueMetadata)
        }
        val shared = MemorySink()
        DailyParquetBuilder.build(thing, day, listOf(source("new.parquet", newBytes, 2), source("renamed.parquet", renamed, 4)), shared, nowEpochS = 0)
        val sharedFile = ParquetFile.wrap(shared.toByteArray())
        val last = ParquetFile.wrap(newBytes).leaves.last().name
        assertNull(sharedFile.leaf(last).fieldId)
        assertNull(sharedFile.leaf("${last}_renamed").fieldId)
        val ids = sharedFile.leaves.mapNotNull { it.fieldId }
        assertEquals(ids.size, ids.toSet().size, "every field id names one column")
    }
}

/**
 * [bytes], a NANOS device file, in the current device layout: UTC instants renamed to `*_us` with
 * MICROS values, and every leaf given its 1-based position plus [idShift] as field id. [seqOffset]
 * shifts every sequence number.
 */
internal fun microsLayout(bytes: ByteArray, seqOffset: Long = 0, idShift: Int = 0): ByteArray {
    val raw = ParquetFile.wrap(bytes)
    val columns = raw.leaves.mapIndexed { i, leaf ->
        val name = UtcColumns.canonical(leaf.name)
        ParquetWriter.Column(name, leaf.type, timestampUtc = if (UtcColumns.isUtcInstant(leaf)) TimeUnit.MICROS else null, fieldId = i + 1 + idShift)
    }
    val values = raw.leaves.map { leaf ->
        val col = raw.readColumn(leaf)
        when {
            UtcColumns.isUtcInstant(leaf) -> UtcColumns.toMicros(col, leaf)
            leaf.name == ParquetIndexer.SEQ && seqOffset != 0L -> (col as ColumnValues.Int64).let { c -> ColumnValues.Int64(LongArray(c.size) { c.values[it] + seqOffset }, c.valid) }
            else -> col
        }
    }
    return writeParquet(columns, values, raw.metadata.keyValueMetadata)
}

/** One row group of [values] under [columns]. */
internal fun writeParquet(columns: List<ParquetWriter.Column>, values: List<ColumnValues>, meta: Map<String, String> = emptyMap()): ByteArray {
    val sink = MemorySink()
    val writer = ParquetWriter(sink, columns, keyValueMetadata = meta)
    writer.begin()
    writer.rowGroup(values)
    writer.finish()
    return sink.toByteArray()
}
