package earth.walkthru.aqsync.data.history.parquet

import earth.walkthru.aqsync.data.history.CoreS3Files
import earth.walkthru.aqsync.data.history.ParquetIndexer
import earth.walkthru.aqsync.data.history.UtcColumns
import earth.walkthru.aqsync.data.history.fixtureBytes
import earth.walkthru.aqsync.data.history.footer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Two layers of real-hardware coverage:
 *
 * 1. The committed capture of the **first file firmware v6 wrote on the board** (2026-09-17, two row
 *    groups of 90 + 15 rows, statistics, TIMESTAMP): always runs.
 * 2. Every `.parquet` under the sibling firmware repo (`../aq-parquet/exports`, 764 files from
 *    the SD card, and `firmware/arduino-m5unified/artifacts/output`): runs only where that checkout
 *    exists, so CI without it still passes. Each file is parsed, indexed and fully decoded; v6 files
 *    additionally cross-check footer statistics against the decoded integer columns.
 */
class RealArchiveTest {

    private fun resource(name: String) = fixtureBytes(name)

    @Test
    fun `first v6 board file - two row groups, statistics agree with rows`() {
        val pf = ParquetFile.wrap(resource("parquet/device/v3-board-2rg-105rows.parquet"), "board v6")
        val md = pf.metadata
        assertEquals("cores3-telemetry-v3", md.footer?.schema)
        assertEquals(2, pf.rowGroupCount)
        assertEquals(listOf(90L, 15L), md.rowGroups.map { it.numRows })
        assertEquals(105L, pf.numRows)
        assertEquals(77, pf.leaves.size)
        assertEquals(ParquetLogicalType.TimeUnit.NANOS, pf.leaf(UtcColumns.EVENT_NS).timestampUtcUnit)
        assertEquals(ParquetLogicalType.TimeUnit.NANOS, pf.leaf("clock_anchor_utc_ns").timestampUtcUnit)
        assertEquals(77, md.columnOrders.size)

        val index = ParquetIndexer.index(pf, templates = CoreS3Files)
        assertTrue("v6 statistics fast path", index.fromStatistics)
        assertEquals(105L, index.utcRows)
        assertEquals(2L, index.firstSeq)
        assertEquals(106L, index.lastSeq)
        assertEquals(105L, index.validRows)
        // 09:42:35Z … 09:59:55Z on 2026-09-17 (PyArrow shows the same instants).
        assertEquals(1_789_638_155L, index.firstUtcNs!! / 1_000_000_000L)
        assertEquals(1_789_639_195L, index.lastUtcNs!! / 1_000_000_000L)

        // Statistics of every integer column agree with the decoded rows, per row group.
        for (leaf in pf.leaves) {
            if (leaf.type == ParquetType.FLOAT) continue
            for (g in 0 until pf.rowGroupCount) {
                val decoded = ParquetIndexer.rangeByDecoding(pf.readColumn(leaf, g))
                val s = md.rowGroups[g].columns[leaf.index].statistics!!
                assertEquals("${leaf.name} rg$g nulls", md.rowGroups[g].numRows - decoded.count, s.nullCount)
                if (decoded.count > 0) {
                    assertEquals("${leaf.name} rg$g min", decoded.min, s.minLong(leaf.type))
                    assertEquals("${leaf.name} rg$g max", decoded.max, s.maxLong(leaf.type))
                }
            }
        }
        // Row groups are contiguous in `sequence`.
        val seq = pf.readColumn("sequence") as ColumnValues.Int64
        for (i in 1 until seq.size) assertEquals(seq.values[i - 1] + 1, seq.values[i])
    }

    @Test
    fun `every real file in the sibling firmware checkout decodes`() {
        // Gradle can run with the module or root directory as its working directory.
        val checkout = listOf("../../../aq-parquet", "../../aq-parquet", "../aq-parquet")
            .map { File(it).absoluteFile.normalize() }
            .firstOrNull { File(it, "pixi.toml").isFile }
        val roots = listOfNotNull(checkout).flatMap { listOf(File(it, "exports"), File(it, "firmware/arduino-m5unified/artifacts/output")) }.filter { it.isDirectory }
        assumeTrue("firmware checkout not present next to this repo", roots.isNotEmpty())
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "parquet" }.toList() }
        assumeTrue(files.isNotEmpty())
        var rows = 0L
        var columns = 0L
        var statsChecked = 0
        val t0 = System.nanoTime()
        for (f in files) {
            val pf = try {
                ParquetFile.open(f)
            } catch (e: ParquetFormatException) {
                throw AssertionError("${f.name}: ${e.message}", e)
            }
            val index = ParquetIndexer.index(pf, templates = CoreS3Files)
            assertEquals(f.name, pf.numRows, index.rows)
            var decodedRows = -1L
            for (leaf in pf.leaves) {
                val col = pf.readColumn(leaf)
                assertEquals("${f.name} '${leaf.name}' size", pf.numRows, col.size.toLong())
                decodedRows = col.size.toLong()
                columns++
                if (index.fromStatistics && leaf.type != ParquetType.FLOAT) {
                    val decoded = ParquetIndexer.rangeByDecoding(col)
                    val stats = ParquetIndexer.rangeFromStatistics(pf, leaf)
                    if (stats != null) {
                        assertEquals("${f.name} '${leaf.name}' statistics", decoded, stats)
                        statsChecked++
                    }
                }
            }
            rows += decodedRows.coerceAtLeast(0)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("RealArchiveTest: ${files.size} files, $rows rows, $columns columns, $statsChecked statistics cross-checks in $ms ms")
        assertTrue(rows > 0)
    }
}
