package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorStateTest {
    private val crowpanel = Thing.join("crowpanel", BundledCatalog.templates.single { it.id == "crowpanel-epaper-telemetry-v1" })
    private val acme = Thing.join("acme", BoardTemplate.parse(ACME_SOIL_PROBE_TEMPLATE, desc = ACME_DESC))

    private fun Thing.state(column: String, raw: Double?): SensorState? {
        val gate = stateGate(column)
        return SensorState.of(gate, gate?.let { datastream(it.column)?.datastream }, raw)
    }

    @Test
    fun theCrowPanelSen6xStatusFourIsValidAndOneIsWarming() {
        val valid = crowpanel.state("pm25_mass_ug_m3", 4.0)!!
        assertEquals("sen6x_status", valid.column)
        assertTrue(valid.valid)
        assertEquals("valid", valid.cls?.name)
        val warming = crowpanel.state("pm25_mass_ug_m3", 1.0)!!
        assertFalse(warming.valid)
        assertEquals("warming", warming.cls?.name)
        assertEquals("Warming up", warming.label)
        assertTrue(crowpanel.state("pm25_mass_ug_m3", 0.0)!!.absent)
    }

    @Test
    fun aFakeBoardStatusRendersItsClassTitle() {
        val dry = acme.state("soil_moisture_pct", 2.0)!!
        assertEquals("probe_status", dry.column)
        assertFalse(dry.valid)
        assertEquals("Probe out of soil", dry.label)
        assertTrue(acme.state("soil_moisture_pct", 1.0)!!.valid)
        assertTrue(acme.state("soil_moisture_pct", 0.0)!!.absent)
    }

    @Test
    fun aClassWithoutATitleShowsItsNameWithSpaces() {
        val cls = CodeClass(7, "battery_low")
        assertEquals("battery low", SensorState("s", 7, cls, valid = false).label)
    }

    @Test
    fun aStatusWithoutClassesStillGatesByTheEqualsTest() {
        val gate = ValidityGate.Equals("s", GateValue.Number(3.0))
        val bare = Datastream("s", "int32", Role.QUALITY_INFORMATION)
        val valid = SensorState.of(gate, bare, 3.0)!!
        assertTrue(valid.valid)
        assertNull(valid.cls)
        assertNull(valid.label)
        assertFalse(SensorState.of(gate, bare, 2.0)!!.valid)
    }

    @Test
    fun noReadingAFractionOrAFiniteGateIsNoState() {
        val gate = ValidityGate.Equals("s", GateValue.Number(3.0))
        assertNull(SensorState.of(gate, null, null))
        assertNull(SensorState.of(gate, null, 2.5))
        assertNull(SensorState.of(gate, null, Double.NaN))
        assertNull(SensorState.of(ValidityGate.Finite("s"), null, 3.0))
        assertNull(crowpanel.stateGate("ambient_temperature_c"))
        assertNull(crowpanel.stateGate("no_such_column"))
    }
}
