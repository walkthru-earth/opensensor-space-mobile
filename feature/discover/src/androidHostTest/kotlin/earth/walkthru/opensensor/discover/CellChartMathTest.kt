package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellStat
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.PercentileScale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellChartMathTest {
    private val pm = FeedProperty("pm25", "PM2.5", "µg/m³", "Air", 1.0)
    private val temp = FeedProperty("temp", "Temp", "°C", "Weather", 0.5)
    private val noise = FeedProperty("noise", "Noise", "dB", "Noise", 1.0)

    private fun cell(h3: Long, vararg stats: Pair<String, CellStat>) = CellRow(h3, 3, 0L, mapOf(*stats))

    @Test
    fun domainIsTheLegendPercentileSpanAndSkipsMissingValues() {
        val d = ChartDomain.of(listOf(10.0, 20.0, Double.NaN))!!
        assertEquals(ChartDomain(10.0, 20.0), d)
        assertEquals(0f, d.fraction(4.0))
        assertEquals(0.5f, d.fraction(15.0))
        assertEquals(1f, d.fraction(99.0))
        assertNull(ChartDomain.of(emptyList()))
        assertEquals(0.5f, ChartDomain(3.0, 3.0).fraction(3.0))
        // One outlier among 40 areas stays out of the span, as on the map legend.
        val clipped = ChartDomain.of((1..39).map { it.toDouble() } + 1000.0)!!
        assertEquals(PercentileScale.of((1..39).map { it.toDouble() } + 1000.0)!!.high, clipped.high)
        assertTrue(clipped.high < 1000.0)
    }

    @Test
    fun histogramClampsValuesPastTheEndsIntoTheEndBins() {
        val d = ChartDomain(0.0, 10.0)
        assertContentEquals(intArrayOf(3, 0, 1, 0, 3), histogram(listOf(-4.0, 0.0, 1.9, 5.0, 9.0, 10.0, 11.0, Double.NaN), d, 5))
        assertEquals(4, binOf(10.0, d, 5))
        assertEquals(2, binOf(3.0, ChartDomain(3.0, 3.0), 5))
    }

    @Test
    fun binCountStaysReadable() {
        assertEquals(6, binCount(1))
        assertEquals(10, binCount(20))
        assertEquals(24, binCount(1000))
    }

    @Test
    fun percentBelowNeedsEnoughAreas() {
        assertNull(percentBelow(listOf(1.0, 2.0), 2.0))
        assertEquals(50, percentBelow(listOf(1.0, 2.0, 3.0, 4.0), 3.0))
        assertEquals(0, percentBelow(listOf(5.0, 6.0, 7.0), 5.0))
    }

    @Test
    fun metricRangesFollowTheDictionaryAndKeepNullsAbsent() {
        val here = cell(1, "pm25" to CellStat(4, 14.0, 6.0, 31.0), "temp" to CellStat(4, 21.5, null, null), "noise" to CellStat(4, null, 40.0, 50.0))
        val rows = listOf(here, cell(2, "pm25" to CellStat(3, 8.0, 5.0, 9.0)), cell(3, "pm25" to CellStat(3, 20.0, 18.0, 22.0)))
        val ranges = metricRanges(here, rows, listOf(temp, pm, noise))
        assertEquals(listOf("temp", "pm25"), ranges.map { it.property.id })
        val p = ranges[1]
        assertEquals(ChartDomain(8.0, 20.0), p.domain)
        assertEquals(0.5, p.colorFraction)
        assertEquals(6.0, p.min)
        val t = ranges[0]
        assertNull(t.min)
        assertNull(t.max)
        assertEquals(ChartDomain(21.5, 21.5), t.domain)
    }
}
