package space.opensensor.data.history

import space.opensensor.model.BundledCatalog
import space.opensensor.model.GateValue
import space.opensensor.model.Thing
import space.opensensor.model.ThingDatastream
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
    private val things: List<Thing> = BundledCatalog.templates.map { assertNotNull(BundledCatalog.thing("parity", it.dict)) }

    /** The things that show [column], a column a profile hides is never shown. */
    private fun showing(column: String): List<Pair<Thing, ThingDatastream>> = things.mapNotNull { thing ->
        thing.datastream(column)?.takeIf { column !in thing.profile.hidden }?.let { thing to it }
    }

    private fun gateMatches(spec: MetricSpec, gate: ValidityGate?): Boolean = when (val expected = spec.gate) {
        null -> gate == null || gate == ValidityGate.Finite(spec.column)
        else -> gate == expected
    }

    @Test
    fun everyStoredMetricHasTheSameLabelUnitGateSettleAndScaleOnEveryBoardThatShowsIt() {
        Metrics.all.filter { it.auxColumn == null }.forEach { spec ->
            val shown = showing(spec.column)
            assertTrue(shown.isNotEmpty(), "no bundled board shows ${spec.id}")
            shown.forEach { (thing, ds) ->
                val at = "${spec.id} on ${thing.template.id}"
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
        val spec = AirMetrics.PM25_CORRECTED
        val boards = things.filter { s -> listOf(spec.column, spec.auxColumn!!).all { c -> showing(c).any { it.first === s } } }
        assertTrue(boards.isNotEmpty(), "no bundled board shows both ${spec.column} and ${spec.auxColumn}")
        boards.forEach { thing ->
            assertTrue(gateMatches(spec, thing.datastream(spec.column)!!.datastream.validWhen), thing.template.id)
            assertEquals(spec.settleSeconds.toDouble(), thing.datastream(spec.column)!!.datastream.settleSeconds, thing.template.id)
        }
    }

    @Test
    fun thePrimaryReadingIsTheMetricsPrimaryOnEveryBoard() {
        things.forEach { thing ->
            assertEquals(AirMetrics.PM25.column, thing.primary.first().name, thing.template.id)
            assertEquals(AirMetrics.PM25.label, thing.primary.first().label("en"), thing.template.id)
        }
    }

    @Test
    fun aHiddenColumnIsNeverPrimary() {
        things.forEach { thing ->
            thing.primary.forEach { assertTrue(it.name !in thing.profile.hidden, "${thing.template.id} shows hidden ${it.name}") }
        }
    }
}
