package space.opensensor.data.history

import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
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
 * Every bundled board template, joined with its derived profile, reproduces the [Metrics] catalog, so
 * a column two boards share has one label, unit, gate, settle time and health scale. Plausible ranges
 * come only from the templates.
 */
class MetricsParityTest {
    init {
        AirFamily.install()
    }

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
        Metrics.all.filter { spec -> spec.auxColumn == null && things.any { it.datastream(spec.column) != null } }.forEach { spec ->
            val shown = showing(spec.column)
            assertTrue(shown.isNotEmpty(), "no bundled board shows ${spec.id}")
            shown.forEach { (thing, ds) ->
                val at = "${spec.id} on ${thing.template.id}"
                assertEquals(spec.label, ds.label("en"), at)
                assertEquals(spec.unit, ds.displayUnit, at)
                assertTrue(gateMatches(spec, ds.datastream.validWhen), "$at gate ${ds.datastream.validWhen} is not ${spec.gate}")
                assertEquals(spec.settleSeconds.toDouble(), ds.datastream.settleSeconds, at)
                assertEquals(spec.decimals, ds.decimals, at)
                assertEquals(spec.healthScale?.id, ds.scale?.id, at)
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
    fun theFirstScaledMeasurementIsThePrimaryOnEveryBoard() {
        // No bundled template names opensensor:primary yet, so PM2.5 wins as the first column with a scale.
        things.forEach { thing ->
            val primary = assertNotNull(thing.primary, thing.template.id)
            assertEquals("who-2021-pm25", primary.scale?.id, thing.template.id)
            assertTrue(primary.name !in thing.profile.hidden, "${thing.template.id} shows hidden ${primary.name}")
            assertTrue(Metrics.of(thing.template, thing.profile).single { it.primary && !it.derived }.column == primary.name, thing.template.id)
        }
        assertEquals(AirMetrics.PM25.column, things.first { it.template.id.contains("cores3") }.primary?.name)
    }
}
