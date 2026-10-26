package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellScale
import earth.walkthru.aqsync.publicfeed.CellStat
import earth.walkthru.aqsync.publicfeed.DiscoverCollection
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.PercentileScale
import earth.walkthru.aqsync.publicfeed.SequentialRamp
import space.opensensor.model.LocalizedText
import space.opensensor.model.Scale
import space.opensensor.model.ScaleStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellChartMathTest {
    // A feed describes PM with the scales the app ships ([DiscoverCollection.BUNDLED]).
    private val pm = DiscoverCollection.BUNDLED.describe(listOf(FeedProperty("pm25", "PM2.5", "µg/m³", "Air", 1.0))).single()
    private val pm10 = DiscoverCollection.BUNDLED.describe(listOf(FeedProperty("pm10", "PM10", "µg/m³", "Air", 1.0))).single()
    private fun scaleOf(p: FeedProperty, values: List<Double> = listOf(1.0)) = CellScale.of(p, values)
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
        // PM2.5 reads on the WHO axis, zero to the edge above the visible max, in its step colour.
        val p = ranges[1]
        assertEquals(ChartDomain(0.0, 25.0), p.domain)
        assertEquals(WhoStep.GUIDELINE.color, p.color)
        assertEquals(WhoStep.GUIDELINE.ordinal, p.step)
        assertEquals(15.0, p.guideline)
        assertTrue(p.stepAxis)
        assertEquals(6.0, p.min)
        // Temperature keeps the percentile ramp and has no step.
        val t = ranges[0]
        assertNull(t.min)
        assertNull(t.max)
        assertNull(t.step)
        assertNull(t.steps)
        assertFalse(t.stepAxis)
        assertEquals(ChartDomain(21.5, 21.5), t.domain)
        assertEquals(SequentialRamp.at(0.5), t.color)
    }

    @Test
    fun whoAxisStartsAtZeroAndBinsFollowTheEdges() {
        assertEquals(ChartDomain(0.0, 15.0), ChartDomain.forScale(scaleOf(pm), listOf(3.0, 9.0)))
        assertEquals(ChartDomain(0.0, 37.5), ChartDomain.forScale(scaleOf(pm), listOf(10.0, 30.0)))
        assertEquals(ChartDomain(0.0, 45.0), ChartDomain.forScale(scaleOf(pm10), listOf(30.0)))
        assertNull(ChartDomain.forScale(scaleOf(pm), emptyList()))
        // Without a step scale the percentile span stays.
        val co2 = FeedProperty("co2", "CO2", "ppm", "Air")
        assertEquals(ChartDomain(10.0, 20.0), ChartDomain.forScale(scaleOf(co2, listOf(10.0, 20.0)), listOf(10.0, 20.0)))
        // PM2.5 with no scale in its Collection is neutral too. The id alone never picks WHO.
        assertEquals(ChartDomain(10.0, 20.0), ChartDomain.forScale(scaleOf(pm.copy(scale = null), listOf(10.0, 20.0)), listOf(10.0, 20.0)))

        val d = ChartDomain(0.0, 25.0)
        val bins = chartBins(scaleOf(pm), d, 3)
        assertEquals(10, bins)
        // 15 is at or below the guideline, so it counts in the last guideline bar.
        assertEquals(5, binOf(15.0, d, bins, upperInclusive = true))
        assertEquals(6, binOf(15.1, d, bins, upperInclusive = true))
        assertEquals(0, binOf(0.0, d, bins, upperInclusive = true))
        assertEquals(WhoStep.GUIDELINE, WhoGuidelines.PM25.stepOf(binMid(5, d, bins)))
        assertEquals(WhoStep.IT4, WhoGuidelines.PM25.stepOf(binMid(6, d, bins)))
        assertEquals(binCount(40), chartBins(scaleOf(co2), ChartDomain(0.0, 25.0), 40))
        assertEquals("37.5", formatEdge(37.5))
        assertEquals("15", formatEdge(15.0))
    }

    @Test
    fun aPublishedScaleThatIsNotWhoStillStepsTheChart() {
        // A soil scale from a Collection, no colour hints, no guideline. Its edges set the axis.
        val soilScale = Scale(
            "soil",
            "https://example.test/soil",
            listOf(0.2, 0.4, null).map { ScaleStep(it, LocalizedText(mapOf("en" to "s$it"))) },
        )
        val soil = FeedProperty("soil_moisture", "Soil moisture", "m³/m³", "Soil", scale = soilScale)
        val scale = scaleOf(soil)
        assertTrue(scale is CellScale.Steps && scale.scale.who == null)
        assertNull(scale.who)
        assertEquals(ChartDomain(0.0, 0.4), ChartDomain.forScale(scale, listOf(0.1, 0.3)))
        // A scale with an edge at or below zero cannot start the axis at zero, so the percentile span stays.
        val below = soil.copy(scale = soilScale.copy(steps = listOf(-1.0, 0.4, null).map { ScaleStep(it, LocalizedText(mapOf("en" to "s"))) }))
        assertNull(stepAxisOf(scaleOf(below)))
        assertEquals(ChartDomain(0.1, 0.3), ChartDomain.forScale(scaleOf(below), listOf(0.1, 0.3)))
        val here = cell(1, "soil_moisture" to CellStat(1, 0.3, 0.25, 0.35))
        val r = metricRanges(here, listOf(here), listOf(soil)).single()
        assertEquals(1, r.step)
        assertNull(r.guideline)
        assertTrue(r.stepAxis)
    }

    @Test
    fun aBarsFilterHoldsExactlyTheValuesTheBarCounts() {
        // The map and the list keep the areas a touched bar counts, nothing more, nothing less,
        // including values clamped into the outer bars and values on a WHO edge.
        val values = listOf(-3.0, 0.0, 7.5, 15.0, 15.1, 22.0, 25.0, 30.0, 37.5, 80.0)
        listOf(true, false).forEach { inclusive ->
            val domain = ChartDomain(0.0, 37.5)
            val bins = 5
            val counts = histogram(values, domain, bins, upperInclusive = inclusive)
            for (i in 0 until bins) {
                val band = bandOf(i, domain, bins, inclusive)
                assertEquals(counts[i], values.count { it in band }, "bar $i, upper inclusive $inclusive")
            }
        }
    }

    @Test
    fun aFlatChartsOccupiedBarKeepsItsOwnReadings() {
        // Equal readings make a flat domain. All of them sit in the middle bar, so touching that
        // bar keeps them all and every other bar keeps none (#110).
        val values = listOf(20.0, 20.0, 20.0)
        val domain = ChartDomain.of(values)!!
        listOf(true, false).forEach { inclusive ->
            val bins = 6
            val counts = histogram(values, domain, bins, upperInclusive = inclusive)
            assertEquals(3, counts[bins / 2])
            for (i in 0 until bins) {
                val band = bandOf(i, domain, bins, inclusive)
                assertEquals(counts[i], values.count { it in band }, "bar $i, upper inclusive $inclusive")
            }
        }
    }

    @Test
    fun stepBlocksCountAreasPerStepAndSkipMissingValues() {
        val blocks = stepBlocks(listOf(3.0, 15.0, 15.1, 40.0, 200.0, Double.NaN), WhoGuidelines.PM25)
        assertEquals(WhoStep.entries.toList(), blocks.map { it.step })
        assertEquals(listOf(2f, 1f, 0f, 1f, 0f, 1f), blocks.map { it.weight })
    }

    @Test
    fun aStepBandHoldsExactlyTheValuesOfThatStep() {
        val pm = WhoGuidelines.PM25
        val guideline = stepBand(pm, WhoStep.GUIDELINE)
        assertTrue(0.0 in guideline && 15.0 in guideline)
        assertTrue(15.1 !in guideline)
        val it4 = stepBand(pm, WhoStep.IT4)
        assertTrue(15.1 in it4 && 25.0 in it4 && 15.0 !in it4 && 25.1 !in it4)
        val top = stepBand(pm, WhoStep.ABOVE_IT1)
        assertTrue(75.1 in top && 10_000.0 in top && 75.0 !in top)
        listOf(0.0, 15.0, 15.1, 37.5, 50.0, 74.9, 75.0, 75.1, 500.0).forEach { v ->
            assertEquals(pm.stepOf(v), WhoStep.entries.single { v in stepBand(pm, it) }, "value $v")
        }
    }

    @Test
    fun aMoodBlockTapPicksItsStepAndASecondTapLetsGo() {
        val pm = WhoGuidelines.PM25
        val bands = listOf(stepBand(pm, WhoStep.GUIDELINE), stepBand(pm, WhoStep.IT2))
        val picked = spreadTap(bands, 1, highlight = null)
        assertEquals(bands[1], picked)
        assertEquals(1, spreadSelection(bands, picked))
        assertNull(spreadTap(bands, 1, highlight = picked))
        assertEquals(bands[0], spreadTap(bands, 0, highlight = picked))
    }

    @Test
    fun aHighlightWhoseBlockIsGoneSelectsNothing() {
        val pm = WhoGuidelines.PM25
        val bands = listOf(stepBand(pm, WhoStep.GUIDELINE))
        assertNull(spreadSelection(bands, null))
        assertNull(spreadSelection(bands, stepBand(pm, WhoStep.IT1)))
        assertNull(spreadSelection(bands, stepBand(WhoGuidelines.PM10, WhoStep.GUIDELINE)))
    }
}
