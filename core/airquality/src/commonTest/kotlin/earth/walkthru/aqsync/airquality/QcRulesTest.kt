package earth.walkthru.aqsync.airquality

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each quality check on synthetic rows, both sides of every threshold, missing fields and gaps. */
class QcRulesTest {
    private val step = 10_000L

    private fun row(i: Int, pm25: Double = Double.NaN, pm10: Double = Double.NaN, counts: IntArray? = null, rh: Double = Double.NaN, pm1: Double = Double.NaN) =
        QcRow(timeMs = 1_789_638_000_000L + i * step, pm1 = pm1, pm25 = pm25, pm10 = pm10, counts = counts, rh = rh)

    private fun counts(base: Int) = intArrayOf(base, base / 3, base / 20, base / 200, 0, 0)

    private fun flagged(flags: IntArray, bit: Int): List<Int> = flags.indices.filter { flags[it] and bit != 0 }

    @Test
    fun bitValuesAreStableAndDistinct() {
        assertEquals(0, QcFlags.NONE)
        assertEquals(1, QcFlags.PM25_ABOVE_PM10)
        assertEquals(2, QcFlags.FLATLINE)
        assertEquals(4, QcFlags.SPIKE)
        assertEquals(8, QcFlags.HIGH_HUMIDITY)
    }

    @Test
    fun noRowsAndOneRowGiveNoFlags() {
        assertContentEquals(IntArray(0), QcRules.flags(emptyList()))
        assertContentEquals(intArrayOf(0), QcRules.flags(listOf(row(0, pm25 = 10.0, pm10 = 12.0, counts = counts(900), rh = 50.0))))
    }

    @Test
    fun pm25AbovePm10OnlyBeyondTheRoundingTolerance() {
        val rows = listOf(
            row(0, pm25 = 20.0, pm10 = 20.0),
            row(1, pm25 = 21.0, pm10 = 20.0),
            row(2, pm25 = 21.01, pm10 = 20.0),
            row(3, pm25 = 40.0, pm10 = Double.NaN),
            row(4, pm25 = Double.NaN, pm10 = 1.0),
            row(5, pm25 = Double.POSITIVE_INFINITY, pm10 = 1.0),
            row(6, pm25 = 30.0, pm10 = 10.0),
        )
        assertEquals(listOf(2, 6), flagged(QcRules.flags(rows), QcFlags.PM25_ABOVE_PM10))
    }

    @Test
    fun anInfinitePm10OrRhIsMissingNotAFlag() {
        val rows = listOf(
            row(0, pm25 = 10.0, pm10 = Double.NEGATIVE_INFINITY),
            row(1, pm25 = 10.0, pm10 = Double.POSITIVE_INFINITY),
            row(2, rh = Double.POSITIVE_INFINITY),
            row(3, rh = Double.NEGATIVE_INFINITY),
        )
        assertEquals(emptyList(), flagged(QcRules.flags(rows), QcFlags.PM25_ABOVE_PM10))
        assertEquals(emptyList(), flagged(QcRules.flags(rows), QcFlags.HIGH_HUMIDITY))
    }

    @Test
    fun highHumidityOnlyAboveEightyFivePercentAndOnlyWithRh() {
        val rows = listOf(row(0, rh = 85.0), row(1, rh = 85.01), row(2), row(3, rh = 99.0), row(4, rh = 84.99))
        assertEquals(listOf(1, 3), flagged(QcRules.flags(rows), QcFlags.HIGH_HUMIDITY))
    }

    /** [n] rows with frozen counts between two rows whose counts differ. */
    private fun frozenRun(n: Int): List<QcRow> = buildList {
        add(row(0, counts = counts(500)))
        for (i in 1..n) add(row(i, pm25 = 10.0 + i % 3, counts = counts(900)))
        add(row(n + 1, counts = counts(700)))
    }

    @Test
    fun flatlineNeedsThirtyRowsOfUnchangedCounts() {
        assertEquals(emptyList(), flagged(QcRules.flags(frozenRun(29)), QcFlags.FLATLINE))
        assertEquals((1..30).toList(), flagged(QcRules.flags(frozenRun(30)), QcFlags.FLATLINE))
    }

    @Test
    fun flatlineReadsCountsNotMass() {
        val steadyMassMovingCounts = (0 until 40).map { row(it, pm25 = 12.0, pm10 = 15.0, counts = counts(900 + it)) }
        assertEquals(emptyList(), flagged(QcRules.flags(steadyMassMovingCounts), QcFlags.FLATLINE))
        val noCounts = (0 until 40).map { row(it, pm25 = 12.0, pm10 = 15.0) }
        assertEquals(emptyList(), flagged(QcRules.flags(noCounts), QcFlags.FLATLINE))
    }

    @Test
    fun aRowWithoutCountsEndsAFlatline() {
        val rows = (0 until 31).map { row(it, counts = if (it == 15) null else counts(900)) }
        assertEquals(emptyList(), flagged(QcRules.flags(rows), QcFlags.FLATLINE))
    }

    @Test
    fun aCountChangeInOneBinEndsAFlatline() {
        val rows = (0 until 31).map { row(it, counts = counts(900).also { c -> if (it >= 15) c[5] = 1 }) }
        assertEquals(emptyList(), flagged(QcRules.flags(rows), QcFlags.FLATLINE))
    }

    @Test
    fun aGapInTimeEndsAFlatline() {
        // 31 frozen rows at a 10 s step, with one longer step after row 15.
        fun run(longStep: Long) = (0 until 31).map { i ->
            QcRow(timeMs = i * step + if (i > 15) longStep - step else 0, counts = counts(900))
        }
        // Three typical steps is still one run, a millisecond more is a gap.
        assertEquals((0 until 31).toList(), flagged(QcRules.flags(run(3 * step)), QcFlags.FLATLINE))
        assertEquals(emptyList(), flagged(QcRules.flags(run(3 * step + 1)), QcFlags.FLATLINE))
        // An explicit limit overrides the median.
        assertEquals((0 until 31).toList(), flagged(QcRules.flags(run(3 * step + 1), maxStepMs = 4 * step), QcFlags.FLATLINE))
    }

    @Test
    fun aRepeatedOrBackwardTimeEndsAFlatline() {
        for (back in listOf(0L, -step)) {
            val rows = (0 until 31).map { i -> QcRow(timeMs = if (i == 15) (i - 1) * step + back else i * step, counts = counts(900)) }
            assertEquals(emptyList(), flagged(QcRules.flags(rows), QcFlags.FLATLINE), "step $back")
        }
    }

    private fun spike(before: Double, at: Double, after: Double, atCounts: IntArray? = counts(1000), countsOn: Boolean = true) = listOf(
        row(0, pm25 = before, counts = if (countsOn) counts(1000) else null),
        row(1, pm25 = at, counts = if (countsOn) atCounts else null),
        row(2, pm25 = after, counts = if (countsOn) counts(1000) else null),
    )

    private fun spikeAt1(rows: List<QcRow>, maxStepMs: Long? = null): Boolean {
        val flags = if (maxStepMs == null) QcRules.flags(rows) else QcRules.flags(rows, maxStepMs)
        return flagged(flags, QcFlags.SPIKE) == listOf(1)
    }

    @Test
    fun spikeNeedsMoreThanTwentyMicrogramsFromBothNeighbours() {
        assertEquals(false, spikeAt1(spike(10.0, 30.0, 10.0)))
        assertEquals(true, spikeAt1(spike(10.0, 30.01, 10.0)))
        // The return side counts too.
        assertEquals(false, spikeAt1(spike(10.0, 31.0, 11.0)))
        assertEquals(true, spikeAt1(spike(10.0, 31.0, 10.99)))
    }

    @Test
    fun spikeAboveOneHundredNeedsMoreThanTwentyPercent() {
        assertEquals(false, spikeAt1(spike(200.0, 240.0, 200.0)))
        assertEquals(true, spikeAt1(spike(200.0, 240.01, 200.0)))
    }

    @Test
    fun theRatioReadsTheLargerNeighbour() {
        // Neighbours 150 and 200 give 40, the larger one. The smaller would give 30.
        assertEquals(false, spikeAt1(spike(150.0, 240.0, 200.0)))
        assertEquals(true, spikeAt1(spike(150.0, 240.01, 200.0)))
        assertEquals(false, spikeAt1(spike(200.0, 240.0, 150.0)))
        assertEquals(true, spikeAt1(spike(200.0, 240.01, 150.0)))
    }

    @Test
    fun aDipIsASpikeTooButAStepIsNot() {
        assertEquals(true, spikeAt1(spike(50.0, 10.0, 50.0)))
        assertEquals(false, spikeAt1(spike(10.0, 50.0, 50.0)))
        assertEquals(false, spikeAt1(spike(10.0, 50.0, 90.0)))
    }

    @Test
    fun matchingCountsClearASpike() {
        // Mass 10 to 40 is a 0.75 relative change, so a bin must move by at least 0.375 the same way.
        val at1600 = counts(1000).also { it[0] = 1600 }
        val at1599 = counts(1000).also { it[0] = 1599 }
        assertEquals(false, spikeAt1(spike(10.0, 40.0, 10.0, atCounts = at1600)))
        assertEquals(true, spikeAt1(spike(10.0, 40.0, 10.0, atCounts = at1599)))
        // Counts moving the other way do not match.
        val falling = counts(1000).also { it[0] = 100 }
        assertEquals(true, spikeAt1(spike(10.0, 40.0, 10.0, atCounts = falling)))
    }

    @Test
    fun spikeNeedsCountsAndThreeFiniteNeighbours() {
        assertEquals(false, spikeAt1(spike(10.0, 80.0, 10.0, countsOn = false)))
        assertEquals(false, spikeAt1(spike(10.0, 80.0, 10.0, atCounts = intArrayOf(1, 2))))
        assertEquals(false, spikeAt1(spike(Double.NaN, 80.0, 10.0)))
        assertEquals(false, spikeAt1(spike(10.0, 80.0, Double.NaN)))
        assertEquals(false, spikeAt1(spike(10.0, Double.NaN, 10.0)))
    }

    @Test
    fun aGapOnEitherSideClearsASpike() {
        val rows = spike(10.0, 80.0, 10.0)
        assertEquals(true, spikeAt1(rows, maxStepMs = step))
        assertEquals(false, spikeAt1(rows, maxStepMs = step - 1))
        val gapBefore = listOf(rows[0], rows[1], rows[2], row(3, pm25 = 10.0, counts = counts(1000)))
            .mapIndexed { i, r -> if (i == 0) QcRow(r.timeMs - 1, pm25 = r.pm25, counts = r.counts) else r }
        assertEquals(emptyList(), flagged(QcRules.flags(gapBefore, maxStepMs = step), QcFlags.SPIKE))
        val gapAfter = listOf(row(-1, pm25 = 10.0, counts = counts(1000)), rows[0], rows[1])
            .plus(QcRow(rows[2].timeMs + 1, pm25 = 10.0, counts = counts(1000)))
        assertEquals(emptyList(), flagged(QcRules.flags(gapAfter, maxStepMs = step), QcFlags.SPIKE))
        // The same rows without the extra millisecond flag the spike, so the gap is what clears it.
        val joined = listOf(row(-1, pm25 = 10.0, counts = counts(1000))) + rows
        assertEquals(listOf(2), flagged(QcRules.flags(joined, maxStepMs = step), QcFlags.SPIKE))
    }

    @Test
    fun spikeChecksPm1AndPm10Too() {
        val pm10 = listOf(row(0, pm10 = 20.0, counts = counts(1000)), row(1, pm10 = 90.0, counts = counts(1000)), row(2, pm10 = 20.0, counts = counts(1000)))
        assertEquals(listOf(1), flagged(QcRules.flags(pm10), QcFlags.SPIKE))
        val pm1 = listOf(row(0, pm1 = 5.0, counts = counts(1000)), row(1, pm1 = 60.0, counts = counts(1000)), row(2, pm1 = 5.0, counts = counts(1000)))
        assertEquals(listOf(1), flagged(QcRules.flags(pm1), QcFlags.SPIKE))
    }

    @Test
    fun flagsCombineOnOneRow() {
        val rows = listOf(
            row(0, pm25 = 10.0, pm10 = 12.0, counts = counts(1000)),
            row(1, pm25 = 80.0, pm10 = 12.0, counts = counts(1000), rh = 95.0),
            row(2, pm25 = 10.0, pm10 = 12.0, counts = counts(1000)),
        )
        assertEquals(QcFlags.PM25_ABOVE_PM10 or QcFlags.SPIKE or QcFlags.HIGH_HUMIDITY, QcRules.flags(rows)[1])
    }

    @Test
    fun typicalStepIsTheMedianPositiveStep() {
        val times = longArrayOf(0, 10, 20, 20, 50, 60, 1_000)
        assertEquals(10, QcRules.typicalStepMs(times.map { QcRow(it) }))
        assertEquals(0, QcRules.typicalStepMs(listOf(QcRow(5), QcRow(5))))
        assertEquals(0, QcRules.typicalStepMs(emptyList()))
    }

    @Test
    fun anEvenNumberOfStepsTakesTheUpperMedian() {
        // With an even count the upper of the two middle steps is taken, pinned so the gap limit never moves.
        assertEquals(30, QcRules.typicalStepMs(listOf(QcRow(0), QcRow(10), QcRow(40))))
        assertEquals(30, QcRules.typicalStepMs(listOf(QcRow(0), QcRow(10), QcRow(30), QcRow(60), QcRow(100))))
    }

    @Test
    fun valuesAreNeverChanged() {
        val rows = frozenRun(30) + spike(10.0, 80.0, 10.0).map { QcRow(it.timeMs + 40 * step, it.pm1, it.pm25, it.pm10, it.counts, 90.0) }
        val before = rows.map { listOf(it.timeMs, it.pm1, it.pm25, it.pm10, it.rh, it.counts?.toList()) }
        val flags = QcRules.flags(rows)
        assertTrue(flags.any { it != 0 })
        assertEquals(before, rows.map { listOf(it.timeMs, it.pm1, it.pm25, it.pm10, it.rh, it.counts?.toList()) })
    }

    @Test
    fun countsAreNeverChangedWhileTheSpikeCheckReadsThem() {
        // Counts that rise too little, rise enough, and fall, so the count check walks every bin.
        val moving = listOf(
            counts(1000).also { it[0] = 1599 },
            counts(1000).also { it[0] = 1600 },
            counts(1000).also { it[0] = 100 },
        )
        for (atCounts in moving) {
            val rows = spike(10.0, 40.0, 10.0, atCounts = atCounts)
            val before = rows.map { it.counts?.toList() }
            QcRules.flags(rows)
            assertEquals(before, rows.map { it.counts?.toList() }, "counts ${atCounts.toList()}")
        }
    }
}
