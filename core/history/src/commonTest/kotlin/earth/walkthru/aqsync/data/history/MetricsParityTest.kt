package earth.walkthru.aqsync.data.history

import space.opensensor.model.BundledCatalog
import space.opensensor.model.GateValue
import space.opensensor.model.Station
import space.opensensor.model.StationDatastream
import space.opensensor.model.ValidityGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundled board templates joined with their app profiles reproduce the reviewed [Metrics]
 * registry, so moving the screens onto them (#185, #186) changes no label, unit, gate, settle time or
 * health scale. Plausible ranges come only from the templates.
 */
class MetricsParityTest {
    private val stations: List<Station> = BundledCatalog.templates.map { assertNotNull(BundledCatalog.station("parity", it.dict)) }

    /** The stations that show [column], a column a profile hides is never shown. */
    private fun showing(column: String): List<Pair<Station, StationDatastream>> = stations.mapNotNull { station ->
        station.datastream(column)?.takeIf { column !in station.profile.hidden }?.let { station to it }
    }

    private fun gateMatches(spec: MetricSpec, gate: ValidityGate?): Boolean = when (val expected = spec.gate) {
        null -> gate == null || gate == ValidityGate.Finite(spec.column)
        else -> gate == ValidityGate.Equals(expected.column, GateValue.Number(expected.value.toDouble()))
    }

    @Test
    fun everyStoredMetricHasTheSameLabelUnitGateSettleAndScaleOnEveryBoardThatShowsIt() {
        Metrics.all.filter { it.humidityColumn == null }.forEach { spec ->
            val shown = showing(spec.column)
            assertTrue(shown.isNotEmpty(), "no bundled board shows ${spec.id}")
            shown.forEach { (station, ds) ->
                val at = "${spec.id} on ${station.template.id}"
                assertEquals(spec.label, ds.label("en"), at)
                assertEquals(spec.unit, ds.displayUnit, at)
                assertTrue(gateMatches(spec, ds.datastream.validWhen), "$at gate ${ds.datastream.validWhen} is not ${spec.gate}")
                assertEquals(spec.settleSeconds.toDouble(), ds.datastream.settleSeconds, at)
                assertEquals(spec.healthScale?.propertyId, ds.scaleId, at)
                assertNotNull(ds.datastream.plausibleRange, "$at has no plausible range in its template")
            }
        }
    }

    @Test
    fun theCorrectedPm25ReadsColumnsABundledBoardShows() {
        val spec = Metrics.PM25_CORRECTED
        val boards = stations.filter { s -> listOf(spec.column, spec.humidityColumn!!).all { c -> showing(c).any { it.first === s } } }
        assertTrue(boards.isNotEmpty(), "no bundled board shows both ${spec.column} and ${spec.humidityColumn}")
        boards.forEach { station ->
            assertTrue(gateMatches(spec, station.datastream(spec.column)!!.datastream.validWhen), station.template.id)
            assertEquals(spec.settleSeconds.toDouble(), station.datastream(spec.column)!!.datastream.settleSeconds, station.template.id)
        }
    }

    @Test
    fun thePrimaryReadingIsTheMetricsPrimaryOnEveryBoard() {
        stations.forEach { station ->
            assertEquals(Metrics.primary.column, station.primary.first().name, station.template.id)
            assertEquals(Metrics.primary.label, station.primary.first().label("en"), station.template.id)
        }
    }

    @Test
    fun aHiddenColumnIsNeverPrimary() {
        stations.forEach { station ->
            station.primary.forEach { assertTrue(it.name !in station.profile.hidden, "${station.template.id} shows hidden ${it.name}") }
        }
    }
}
