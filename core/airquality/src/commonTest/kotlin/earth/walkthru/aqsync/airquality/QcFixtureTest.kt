package earth.walkthru.aqsync.airquality

import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reads a fixture of `:core:parquet` (`src/test/resources/<path>`). */
expect fun fixtureBytes(path: String): ByteArray

/**
 * The quality checks over a real board archive. The 105 rows of the v3 board fixture are clean air
 * at a 10 s step, so nothing is flagged, and each fault written into a copy of those rows is flagged
 * on exactly the rows it touches.
 */
class QcFixtureTest {
    private val bins = listOf("gt03", "gt05", "gt10", "gt25", "gt50", "gt100").map { "particles_${it}_per_01l" }

    private val rows: List<QcRow> = ParquetFile.wrap(fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")).use { file ->
        val time = file.readColumn("event_time_utc_ns").longValues()
        val pm1 = file.readColumn("pm1_atmospheric_ug_m3").doubleValues()
        val pm25 = file.readColumn("pm25_atmospheric_ug_m3").doubleValues()
        val pm10 = file.readColumn("pm10_atmospheric_ug_m3").doubleValues()
        val rh = file.readColumn("relative_humidity_percent").doubleValues()
        val counts = bins.map { file.readColumn(it).longValues() }
        time.indices.map { i ->
            QcRow(
                timeMs = time[i] / 1_000_000,
                pm1 = pm1[i],
                pm25 = pm25[i],
                pm10 = pm10[i],
                counts = IntArray(bins.size) { b -> counts[b][i].toInt() },
                rh = rh[i],
            )
        }
    }

    private fun copy(r: QcRow, pm25: Double = r.pm25, counts: IntArray? = r.counts, rh: Double = r.rh) =
        QcRow(r.timeMs, r.pm1, pm25, r.pm10, counts?.copyOf(), rh)

    private fun flagged(rows: List<QcRow>, bit: Int): List<Int> = QcRules.flags(rows).let { f -> f.indices.filter { f[it] and bit != 0 } }

    @Test
    fun theCleanBoardArchiveRaisesNoFlag() {
        assertEquals(105, rows.size)
        assertEquals(10_000, QcRules.typicalStepMs(rows) / 1_000 * 1_000)
        // The board has no humidity sensor, so the RH check has nothing to read.
        assertEquals(true, rows.all { it.rh.isNaN() })
        assertEquals(List(105) { 0 }, QcRules.flags(rows).toList())
    }

    @Test
    fun pm25AbovePm10WrittenIntoOneRowIsFlaggedThere() {
        val faulty = rows.mapIndexed { i, r -> if (i == 50) copy(r, pm25 = r.pm10 + 2) else r }
        assertEquals(listOf(50), flagged(faulty, QcFlags.PM25_ABOVE_PM10))
    }

    @Test
    fun frozenCountsAreAFlatlineFromThirtyRows() {
        fun frozen(n: Int) = rows.mapIndexed { i, r -> if (i in 10 until 10 + n) copy(r, counts = rows[10].counts) else r }
        assertEquals(emptyList(), flagged(frozen(29), QcFlags.FLATLINE))
        assertEquals((10 until 40).toList(), flagged(frozen(30), QcFlags.FLATLINE))
    }

    @Test
    fun aMassJumpWithStillCountsIsASpike() {
        val faulty = rows.mapIndexed { i, r -> if (i == 60) copy(r, pm25 = rows[59].pm25 + 60, counts = rows[59].counts) else r }
        assertEquals(listOf(60), flagged(faulty, QcFlags.SPIKE))
    }

    @Test
    fun humidityWrittenIntoTheArchiveIsFlaggedAboveEightyFive() {
        val humid = rows.mapIndexed { i, r -> copy(r, rh = if (i == 5) 90.0 else 60.0) }
        assertEquals(listOf(5), flagged(humid, QcFlags.HIGH_HUMIDITY))
    }
}
