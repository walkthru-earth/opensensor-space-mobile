package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnStatistics
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFileMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test

class ParquetIndexerTest {

    private fun bytes(path: String) = fixtureBytes("parquet/$path")

    /** The `boot_id` key the fixture was written with, before the test migration drops it. */
    private fun oldBootKey(path: String) = ParquetFile.wrap(rawFixtureBytes("parquet/$path")).metadata.keyValueMetadata.getValue(LegacyFooterKeys.BOOT_ID)

    private fun expected(label: String) = Json.parseToJsonElement(
        fixtureBytes("parquet/expected.json").decodeToString()
    ).jsonObject["files"]!!.jsonArray.map { it.jsonObject }.first { it["label"]!!.jsonPrimitive.content == label }

    private fun column(label: String, name: String) =
        expected(label)["column_details"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `real v2 file - index comes from decoding and matches PyArrow`() {
        val index = ParquetIndexer.index(ParquetFile.wrap(bytes("device/v2-lz4-4rows-dated.parquet")), templates = CoreS3Files)
        assertFalse(index.fromStatistics)
        assertEquals(4L, index.rows)
        assertEquals(1, index.rowGroups)
        assertEquals(77, index.columns)
        // The row boot is the text firmware wrote in its old boot_id key.
        assertEquals(oldBootKey("device/v2-lz4-4rows-dated.parquet"), index.bootId)
        assertEquals("telemetry", index.purpose)
        assertTrue(index.isTelemetry)
        val utc = column("v2-lz4-4rows-dated", "event_time_utc_ns")
        assertEquals(utc["valid"]!!.jsonPrimitive.long, index.utcRows)
        assertEquals(utc["min"]!!.jsonPrimitive.long, index.firstUtcNs)
        assertEquals(utc["max"]!!.jsonPrimitive.long, index.lastUtcNs)
        val mono = column("v2-lz4-4rows-dated", "monotonic_us")
        assertEquals(mono["min"]!!.jsonPrimitive.long, index.firstMonoUs)
        assertEquals(mono["max"]!!.jsonPrimitive.long, index.lastMonoUs)
        val seq = column("v2-lz4-4rows-dated", "sequence")
        assertEquals(seq["min"]!!.jsonPrimitive.long, index.firstSeq)
        assertEquals(seq["max"]!!.jsonPrimitive.long, index.lastSeq)
    }

    @Test
    fun `unsynced file has no UTC span but a monotonic one`() {
        val index = ParquetIndexer.index(ParquetFile.wrap(bytes("device/v1-none-64rows-unsynced.parquet")), templates = CoreS3Files)
        assertEquals(0L, index.utcRows)
        assertNull(index.firstUtcNs)
        assertFalse(index.hasUtc)
        assertNotNull(index.firstMonoUs)
        assertTrue(index.lastMonoUs!! > index.firstMonoUs!!)
        assertEquals(64L, index.rows)
    }

    @Test
    fun `v3 file - statistics fast path agrees with decoding`() {
        val file = ParquetFile.wrap(bytes("v3/v3-contract-lz4-anchored.parquet"))
        val index = ParquetIndexer.index(file, templates = CoreS3Files)
        assertTrue("v6 footer statistics used", index.fromStatistics)
        for (name in listOf(UtcColumns.EVENT_NS, ParquetIndexer.MONO, ParquetIndexer.SEQ)) {
            val leaf = file.leaf(name)
            val stats = ParquetIndexer.rangeFromStatistics(file, leaf)
            val decoded = ParquetIndexer.rangeByDecoding(file.readColumn(leaf))
            assertEquals(name, decoded, stats)
        }
        // 89 of 90 rows have pms_status 4 in the contract fixture (row 0 has no PMS frame yet).
        assertEquals(89L, index.validRows)
    }

    @Test
    fun `v3 multi-row-group - statistics span all groups`() {
        val file = ParquetFile.wrap(bytes("v3/v3-hourly-lz4.parquet"))
        val leaf = file.leaf("time_ms")
        val stats = ParquetIndexer.rangeFromStatistics(file, leaf)!!
        val decoded = ParquetIndexer.rangeByDecoding(file.readColumn(leaf))
        assertEquals(decoded, stats)
        assertEquals(360L, stats.count)
    }

    /** The footer of [file] with the statistics of [name] in every row group replaced by [stats]. */
    private fun withStats(file: ParquetFile, name: String, stats: (ColumnStatistics) -> ColumnStatistics): ParquetFileMetadata {
        val index = file.leaf(name).index
        val md = file.metadata
        return md.copy(rowGroups = md.rowGroups.map { rg -> rg.copy(columns = rg.columns.mapIndexed { i, c -> if (i == index) c.copy(statistics = stats(c.statistics!!)) else c }) })
    }

    private fun ColumnStatistics.with(nullCount: Long? = this.nullCount, min: ByteArray? = minValue, max: ByteArray? = maxValue) =
        ColumnStatistics(nullCount, distinctCount, min, max, isMinValueExact, isMaxValueExact, nanCount, legacyMin, legacyMax)

    @Test
    fun `a negative null count in the statistics falls back to decoding`() {
        val file = ParquetFile.wrap(bytes("v3/v3-hourly-lz4.parquet"))
        val leaf = file.leaf("time_ms")
        assertNotNull(ParquetIndexer.rangeFromStatistics(file.metadata, leaf))
        assertNull(ParquetIndexer.rangeFromStatistics(withStats(file, "time_ms") { it.with(nullCount = -5) }, leaf))
    }

    @Test
    fun `a min above the max in the statistics falls back to decoding`() {
        val file = ParquetFile.wrap(bytes("v3/v3-hourly-lz4.parquet"))
        val leaf = file.leaf("time_ms")
        assertNull(ParquetIndexer.rangeFromStatistics(withStats(file, "time_ms") { it.with(min = it.maxValue, max = it.minValue) }, leaf))
    }
}
