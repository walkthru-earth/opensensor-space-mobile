package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellScaleTest {
    private fun row(h3: Long, vararg means: Pair<String, Double>) =
        CellRow(h3, 3, 0L, means.associate { (id, v) -> id to CellStat(1, v, v, v) })

    /** One fixed triangle per cell, enough for the GeoJSON writer. */
    private val grid = object : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = 0L

        override fun cellToLatLng(cell: Long): LatLng = LatLng(0.0, 0.0)

        override fun cellToBoundary(cell: Long): List<LatLng> = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 0.0))

        override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
    }

    @Test
    fun pmUsesFixedWhoStepsAndOthersThePercentileRamp() {
        val pm = CellScale.of("pm25", listOf(1.0, 2.0, 3.0))
        assertIs<CellScale.Health>(pm)
        assertEquals(WhoGuidelines.PM25, pm.scale)
        // A colour depends only on the value, never on the cells in view.
        assertEquals(WhoStep.GUIDELINE.color, pm.color(15.0))
        assertEquals(WhoStep.IT4.color, pm.color(15.1))
        assertEquals(WhoStep.IT3.color, CellScale.of("pm25", listOf(500.0, 900.0))!!.color(30.0))
        assertEquals(WhoStep.ABOVE_IT1.color, pm.color(80.0))
        assertNull(pm.color(Double.NaN))
        assertEquals(WhoStep.IT4.color, CellScale.of("pm10", emptyList())!!.color(50.0))

        val co2 = CellScale.of("co2", (1..100).map { it.toDouble() })
        assertIs<CellScale.Relative>(co2)
        assertEquals(PercentileScale(6.0, 96.0), co2.percentile)
        assertEquals(SequentialRamp.stops.first(), co2.color(0.0))
        assertEquals(SequentialRamp.stops.last(), co2.color(500.0))
        assertNull(CellScale.of("temperature", emptyList()))
    }

    @Test
    fun axisStartsAtZeroAndSnapsUpToAWhoEdge() {
        val pm25 = WhoGuidelines.PM25
        // Clean air still shows the guideline.
        assertEquals(15.0, HealthAxis.high(pm25, listOf(2.0, 4.0, 6.0)))
        assertEquals(15.0, HealthAxis.high(pm25, emptyList()))
        assertEquals(15.0, HealthAxis.high(pm25, listOf(15.0)))
        assertEquals(25.0, HealthAxis.high(pm25, listOf(8.0, 14.0, 20.0)))
        assertEquals(37.5, HealthAxis.high(pm25, listOf(30.0)))
        // One outlier among 40 cells is clipped by the 95th percentile, then capped on the chart.
        assertEquals(50.0, HealthAxis.high(pm25, (1..39).map { it.toDouble() } + 1000.0))
        // Past the last edge the percentile itself is the top.
        assertEquals(120.0, HealthAxis.high(pm25, listOf(120.0)))
        assertEquals(45.0, HealthAxis.high(WhoGuidelines.PM10, listOf(10.0, 20.0)))
        assertEquals(100.0, HealthAxis.high(WhoGuidelines.PM10, listOf(80.0)))
    }

    @Test
    fun binsLineUpWithEveryEdgeInView() {
        val pm25 = WhoGuidelines.PM25
        assertEquals(15, HealthAxis.bins(pm25, 15.0))
        assertEquals(10, HealthAxis.bins(pm25, 25.0))
        assertEquals(15, HealthAxis.bins(pm25, 37.5))
        assertEquals(20, HealthAxis.bins(pm25, 50.0))
        assertEquals(30, HealthAxis.bins(pm25, 75.0))
        assertEquals(18, HealthAxis.bins(WhoGuidelines.PM10, 45.0))
        assertEquals(30, HealthAxis.bins(WhoGuidelines.PM10, 150.0))
        assertNull(HealthAxis.bins(pm25, 120.3))
        assertNull(HealthAxis.bins(pm25, 0.0))
        for (high in pm25.edges) {
            val bins = HealthAxis.bins(pm25, high)!!
            val width = high / bins
            pm25.edges.filter { it <= high }.forEach { edge ->
                val k = edge / width
                assertTrue(kotlin.math.abs(k - kotlin.math.round(k)) < 1e-9, "edge $edge on a bin border at $high")
            }
        }
    }

    @Test
    fun geoJsonCarriesTheWhoStepColourForPm() {
        val rows = listOf(row(1L, "pm25" to 10.0), row(2L, "pm25" to 40.0), row(3L, "co2" to 400.0))
        val pm = CellGeoJson.build(grid, rows, "pm25", CellScale.of("pm25", rows.mapNotNull { it.mean("pm25") })) { false }
        val colours = Regex("\"color\":\"(#[0-9a-f]{6})\"").findAll(pm).map { it.groupValues[1] }.toList()
        assertEquals(listOf(SequentialRamp.hex(WhoStep.GUIDELINE.color), SequentialRamp.hex(WhoStep.IT2.color)), colours)
        // The unmeasured cell keeps no fill.
        assertEquals(3, Regex("\"type\":\"Feature\"").findAll(pm).count())
        assertTrue("\"measured\":false" in pm)

        val co2 = CellGeoJson.build(grid, rows, "co2", CellScale.of("co2", listOf(400.0))) { false }
        val co2Colours = Regex("\"color\":\"(#[0-9a-f]{6})\"").findAll(co2).map { it.groupValues[1] }.toList()
        assertEquals(listOf(SequentialRamp.hex(SequentialRamp.at(0.5))), co2Colours)
    }
}
