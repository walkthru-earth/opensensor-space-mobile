package space.opensensor.family.air

import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.model.BundledCatalog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stored check of #91, [AirQc], is the same [QcRules] the live hero runs, read from columns. On
 * synthetic rows and on the real v3 board archive it flags exactly what the row API flags, and it
 * reads its inputs without changing them.
 */
class AirQcTest {
    private val bins = AirFamily.PARTICLE_BINS
    private val check = AirQc(bins)

    /** [rows] as the check columns the daily builder hands [AirQc], NaN where a value is missing. */
    private fun columns(rows: List<QcRow>): List<DoubleArray> {
        fun col(f: (QcRow) -> Double) = DoubleArray(rows.size) { f(rows[it]) }
        return listOf(col { it.pm1 }, col { it.pm25 }, col { it.pm10 }, col { it.rh }) +
            bins.indices.map { b -> DoubleArray(rows.size) { rows[it].counts?.get(b)?.toDouble() ?: Double.NaN } }
    }

    private fun stored(rows: List<QcRow>): IntArray = check.flags(LongArray(rows.size) { rows[it].timeMs }, columns(rows))

    private val board: List<QcRow> = ParquetFile.wrap(fixtureBytes("parquet/device/v3-board-2rg-180rows.parquet")).use { file ->
        val time = file.readColumn("event_time_utc_ns").longValues()
        val pm1 = file.readColumn(AirFamily.PM1_ATMOSPHERIC).doubleValues()
        val pm25 = file.readColumn(AirFamily.PM25_ATMOSPHERIC).doubleValues()
        val pm10 = file.readColumn(AirFamily.PM10_ATMOSPHERIC).doubleValues()
        val rh = file.readColumn(AirFamily.RELATIVE_HUMIDITY).doubleValues()
        val counts = bins.map { file.readColumn(it).longValues() }
        time.indices.map { i -> QcRow(time[i] / 1_000_000, pm1[i], pm25[i], pm10[i], IntArray(bins.size) { b -> counts[b][i].toInt() }, rh[i]) }
    }

    private fun synthetic(n: Int, pm25: (Int) -> Double = { 12.0 }, counts: (Int) -> IntArray? = { intArrayOf(900 + it, 300, 40, 4, 0, 0) }, rh: (Int) -> Double = { 50.0 }) =
        List(n) { i -> QcRow(1_789_638_000_000L + i * 10_000L, 8.0, pm25(i), 20.0, counts(i), rh(i)) }

    @Test
    fun theIdNamesTheRulesVersionAndTheColumnsAreTheInputsInOrder() {
        assertEquals("air-qc-v${QcRules.VERSION}", check.id)
        assertEquals(listOf(AirFamily.PM1_ATMOSPHERIC, AirFamily.PM25_ATMOSPHERIC, AirFamily.PM10_ATMOSPHERIC, AirFamily.RELATIVE_HUMIDITY) + bins, check.columns)
    }

    @Test
    fun eachCheckOnSyntheticRowsMatchesTheRowApi() {
        val cases = mapOf(
            QcFlags.PM25_ABOVE_PM10 to synthetic(40, pm25 = { if (it == 7) 25.0 else 12.0 }),
            QcFlags.FLATLINE to synthetic(40, counts = { intArrayOf(900, 300, 40, 4, 0, 0) }),
            QcFlags.SPIKE to synthetic(40, pm25 = { if (it == 20) 90.0 else 12.0 }).map { QcRow(it.timeMs, it.pm1, it.pm25, if (it.pm25 > 20) 120.0 else it.pm10, it.counts, it.rh) },
            QcFlags.HIGH_HUMIDITY to synthetic(40, rh = { if (it < 5) QcRules.HIGH_HUMIDITY_PERCENT + 1 else 50.0 }),
        )
        for ((bit, rows) in cases) {
            val expected = QcRules.flags(rows)
            assertContentEquals(expected, stored(rows), "bit $bit")
            assertEquals(true, expected.any { it and bit != 0 }, "bit $bit raised")
        }
    }

    @Test
    fun aRowMissingOneBinHasNoCountsSoItEndsAFlatline() {
        val frozen = synthetic(40, counts = { if (it == 20) null else intArrayOf(900, 300, 40, 4, 0, 0) })
        val flags = stored(frozen)
        assertContentEquals(QcRules.flags(frozen), flags)
        // Twenty rows on each side of the gap, fewer than FLATLINE_ROWS, so neither run is flagged.
        assertEquals(0, flags.count { it and QcFlags.FLATLINE != 0 })
        // One NaN bin is a row without counts too.
        val values = columns(synthetic(40, counts = { intArrayOf(900, 300, 40, 4, 0, 0) }))
        values[4 + 3][20] = Double.NaN
        assertEquals(0, check.flags(LongArray(40) { 1_789_638_000_000L + it * 10_000L }, values).count { it and QcFlags.FLATLINE != 0 })
    }

    @Test
    fun theRealBoardArchiveMatchesTheRowApiAndKeepsItsValues() {
        assertEquals(180, board.size)
        val values = columns(board)
        val before = values.map { it.copyOf() }
        val flags = check.flags(LongArray(board.size) { board[it].timeMs }, values)
        assertContentEquals(QcRules.flags(board), flags)
        for (k in values.indices) assertContentEquals(before[k], values[k])
        // A fault written into the real rows is flagged by the stored check as by the live one.
        val faulty = board.mapIndexed { i, r -> if (i == 90) QcRow(r.timeMs, r.pm1, r.pm10 + 2, r.pm10, r.counts, r.rh) else r }
        val faultFlags = stored(faulty)
        assertContentEquals(QcRules.flags(faulty), faultFlags)
        assertEquals(listOf(90), faultFlags.indices.filter { faultFlags[it] and QcFlags.PM25_ABOVE_PM10 != 0 })
    }

    @Test
    fun theFamilyChecksOnlyTemplatesWithAtmosphericPm25() {
        val waveshare = assertNotNull(BundledCatalog.template(LiveFixtures.waveshareDict))
        val qc = assertNotNull(AirFamily.rowCheck(waveshare))
        assertEquals(AirQc(bins.filter { waveshare.datastream(it) != null }), qc)
        assertNull(AirFamily.rowCheck(waveshare.copy(datastreams = waveshare.datastreams.filter { it.name != AirFamily.PM25_ATMOSPHERIC })))
    }
}
