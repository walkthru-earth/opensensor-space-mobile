package space.opensensor.data.sync

import space.opensensor.data.protocol.ConfigFields
import space.opensensor.data.protocol.Confirm
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.FieldType
import space.opensensor.data.protocol.SetupAck
import space.opensensor.model.ConfigApplies
import space.opensensor.model.ConfigKeySpec
import space.opensensor.model.ConfigKeyType
import space.opensensor.model.ConfigValues
import space.opensensor.model.LocalizedText
import space.opensensor.model.ManifestStep
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupPlanTest {
    private val cell = "8828308281fffff"

    private fun page(mobility: String? = "fixed", cell: String? = "", setupV: Int? = null, ack: Int? = null): DeviceConfig {
        val thing = listOfNotNull(
            mobility?.let { """"mobility":"$it"""" },
            setupV?.let { """"setup_v":$it""" },
            ack?.let { """"setup_ack":$it""" },
        )
        val loc = """"location":{"cell":${cell?.let { "\"$it\"" } ?: "null"},"resolution":8}"""
        return DeviceConfig.parse("{$loc,\"thing\":{${thing.joinToString(",")}}}")
    }

    private fun step(id: String, req: Boolean = false, vararg keys: String) = ManifestStep(StepId(id), req, keys.toList())

    private val panelTheme = ConfigKeySpec("panel.theme", ConfigKeyType.ENUM, ConfigValues.Choices(listOf("light", "dark")), ConfigApplies.IMMEDIATELY, page = 7, label = LocalizedText.of("Theme"))
    private val panelSleep = ConfigKeySpec("panel.sleep_s", ConfigKeyType.INTEGER, ConfigValues.Range(0.0, 600.0), ConfigApplies.NEXT_SAMPLE, page = 7)
    private val fanClean = ConfigKeySpec("sen6x.fan_clean", ConfigKeyType.BOOLEAN, applies = ConfigApplies.ACTION, page = 7)

    private fun manifest(vararg steps: ManifestStep, keys: List<ConfigKeySpec> = listOf(panelTheme, panelSleep, fanClean)) =
        SetupManifest(setup = steps.toList(), configKeys = keys.associateBy { it.key })

    private val crowPanel = manifest(step("name"), step("location", req = true), step("wifi"), step("panel", false, "panel.theme", "panel.sleep_s"))
    private val crowPanelV = SetupAck.version(listOf("name", "location", "wifi", "panel"))

    @Test
    fun `the plan keeps the manifest order and indexes each bit by its manifest place`() {
        val plan = SetupPlan.of(crowPanel, page(cell = cell))
        assertEquals(listOf("name", "location", "wifi", "panel"), plan.steps.map { it.id.id })
        assertEquals(listOf(0, 1, 2, 3), plan.steps.map { it.ackIndex })
        assertEquals(crowPanelV, plan.version)
        assertEquals(PlanStepKind.GENERIC, plan.step(StepId("panel"))?.kind)
        assertEquals(listOf("panel.theme", "panel.sleep_s"), plan.step(StepId("panel"))?.fields?.map { it.field.key })
    }

    @Test
    fun `a planned step with a clear bit leaves setup pending until every bit is set`() {
        assertTrue(SetupPlan.of(crowPanel, page(cell = cell)).pending)
        assertTrue(SetupPlan.of(crowPanel, page(cell = cell, setupV = crowPanelV, ack = 0b0111)).pending)
        val all = SetupPlan.of(crowPanel, page(cell = cell, setupV = crowPanelV, ack = 0b1111))
        assertFalse(all.pending)
        assertTrue(all.steps.all { it.done })
    }

    @Test
    fun `a setup_v that names another list reads as no step done`() {
        val plan = SetupPlan.of(crowPanel, page(cell = cell, setupV = crowPanelV xor 1, ack = 0b1111))
        assertTrue(plan.pending)
        assertTrue(plan.steps.none { it.acked })
        // The next bit starts from nothing, so a stale record is never carried over.
        assertEquals(0b0100, plan.ackWith(StepId.WIFI))
    }

    @Test
    fun `the next ack keeps the bits of the same list`() {
        val plan = SetupPlan.of(crowPanel, page(cell = cell, setupV = crowPanelV, ack = 0b0001))
        assertEquals(0b0101, plan.ackWith(StepId.WIFI))
        assertNull(plan.ackWith(StepId("missing")))
    }

    @Test
    fun `an acked location still waits for a cell on a fixed Thing`() {
        val plan = SetupPlan.of(crowPanel, page(cell = "", setupV = crowPanelV, ack = 0b1111))
        val location = plan.step(StepId.LOCATION)!!
        assertTrue(location.acked)
        assertFalse(location.satisfied)
        assertFalse(location.done)
        assertTrue(plan.pending)
    }

    @Test
    fun `location is required on a fixed Thing even when the manifest says optional`() {
        val plan = SetupPlan.of(manifest(step("location", req = false)), page(cell = ""))
        assertTrue(plan.step(StepId.LOCATION)!!.required)
        assertTrue(plan.pending)
    }

    @Test
    fun `a fixed Thing gets the app location step first when the manifest omits it`() {
        val plan = SetupPlan.of(manifest(step("wifi")), page(cell = ""))
        assertEquals(listOf("location", "wifi"), plan.steps.map { it.id.id })
        val location = plan.steps.first()
        assertTrue(location.required)
        assertNull(location.ackIndex)
        assertEquals(0, plan.step(StepId.WIFI)!!.ackIndex)
        assertNull(plan.ackWith(StepId.LOCATION))
    }

    @Test
    fun `the app location step is done by the cell alone`() {
        val empty = SetupPlan.of(SetupManifest.NONE, page(cell = cell))
        assertEquals(listOf("location"), empty.steps.map { it.id.id })
        assertNull(empty.version)
        assertFalse(empty.pending)
        assertTrue(SetupPlan.of(SetupManifest.NONE, page(cell = "")).pending)
        assertTrue(SetupPlan.of(SetupManifest.NONE, page(cell = "not a cell")).pending)
    }

    @Test
    fun `a moving Thing needs no cell and gets no app location step`() {
        assertEquals(emptyList(), SetupPlan.of(SetupManifest.NONE, page(mobility = "moving", cell = "")).steps)
        val declared = SetupPlan.of(manifest(step("location", req = true)), page(mobility = "moving", cell = "", setupV = SetupAck.version(listOf("location")), ack = 1))
        assertFalse(declared.pending)
    }

    @Test
    fun `before page 1 is read the location step is shown and not satisfied`() {
        val plan = SetupPlan.of(SetupManifest.NONE, null)
        assertEquals(listOf("location"), plan.steps.map { it.id.id })
        assertTrue(plan.pending)
    }

    @Test
    fun `a required step the app does not know leaves setup pending`() {
        val plan = SetupPlan.of(manifest(step("calibrate", req = true)), page(cell = cell, setupV = SetupAck.version(listOf("calibrate")), ack = 1))
        val calibrate = plan.step(StepId("calibrate"))!!
        assertEquals(PlanStepKind.UNSUPPORTED, calibrate.kind)
        assertFalse(calibrate.done)
        assertFalse(calibrate.skippable)
        assertTrue(plan.unsupported)
        assertTrue(plan.pending)
    }

    @Test
    fun `an optional unknown step without typed keys is dropped and never pending`() {
        val m = manifest(step("calibrate"), step("wifi"))
        val v = SetupAck.version(listOf("calibrate", "wifi"))
        val plan = SetupPlan.of(m, page(cell = cell, setupV = v, ack = 0b10))
        assertEquals(listOf("location", "wifi"), plan.steps.map { it.id.id })
        assertEquals(1, plan.step(StepId.WIFI)!!.ackIndex)
        assertFalse(plan.pending)
        assertNull(plan.ackWith(StepId("calibrate")))
    }

    @Test
    fun `a required unknown step with typed keys is a generic form done by its bit`() {
        val m = manifest(step("panel", true, "panel.theme"))
        val v = SetupAck.version(listOf("panel"))
        val open = SetupPlan.of(m, page(cell = cell))
        assertEquals(PlanStepKind.GENERIC, open.step(StepId("panel"))!!.kind)
        assertTrue(open.step(StepId("panel"))!!.required)
        assertFalse(open.unsupported)
        assertTrue(open.pending)
        assertFalse(SetupPlan.of(m, page(cell = cell, setupV = v, ack = 1)).pending)
    }

    @Test
    fun `a manifest key that is secret or reserved or not on the board page never reaches a form`() {
        val keys = listOf(
            ConfigKeySpec(ConfigFields.WIFI_PSK, ConfigKeyType.TEXT, applies = ConfigApplies.IMMEDIATELY, page = 7),
            ConfigKeySpec(ConfigFields.BLE_PIN, ConfigKeyType.TEXT, applies = ConfigApplies.IMMEDIATELY, page = 7),
            ConfigKeySpec(ConfigFields.WIFI_SSID, ConfigKeyType.TEXT, applies = ConfigApplies.IMMEDIATELY, page = 7),
            ConfigKeySpec("lan.token", ConfigKeyType.TEXT, applies = ConfigApplies.IMMEDIATELY, page = 7),
            ConfigKeySpec("thing.setup_ack", ConfigKeyType.INTEGER, ConfigValues.Range(0.0, 1.0), ConfigApplies.IMMEDIATELY, page = 7),
            ConfigKeySpec("panel.elsewhere", ConfigKeyType.TEXT, applies = ConfigApplies.IMMEDIATELY, page = 1),
            ConfigKeySpec("panel.ratio", ConfigKeyType.NUMBER, ConfigValues.Range(0.5, 1.5), ConfigApplies.IMMEDIATELY, page = 7),
            panelTheme,
        )
        val m = manifest(step("panel", false, *keys.map { it.key }.toTypedArray()), keys = keys)
        val plan = SetupPlan.of(m, page(cell = cell))
        assertEquals(listOf("panel.theme"), plan.step(StepId("panel"))!!.fields.map { it.field.key })
        // Only reserved or secret keys, so the step has no form and is dropped.
        val onlyReserved = manifest(step("panel", false, ConfigFields.WIFI_PSK, ConfigFields.WIFI_SSID), keys = keys)
        assertNull(SetupPlan.of(onlyReserved, page(cell = cell)).step(StepId("panel")))
    }

    @Test
    fun `a generic field keeps its template label and a board action always confirms`() {
        val m = manifest(step("sensor", false, "sen6x.fan_clean", "panel.theme"))
        val fields = SetupPlan.of(m, page(cell = cell)).step(StepId("sensor"))!!.fields
        assertEquals(FieldType.Action, fields[0].field.type)
        assertEquals(Confirm.Always, fields[0].field.confirm)
        assertTrue(fields[0].field.needsConfirm("1"))
        assertEquals("Theme", fields[1].label?.get("en"))
        assertEquals(FieldType.Choice(listOf("light", "dark")), fields[1].field.type)
    }

    @Test
    fun `a board step carries its template label and a catalog step none`() {
        val label = LocalizedText(mapOf(LocalizedText.UNTAGGED to "Display", "ar" to "الشاشة"))
        val m = manifest(ManifestStep(StepId("panel"), keys = listOf("panel.theme"), label = label), ManifestStep(StepId.WIFI, label = label))
        val plan = SetupPlan.of(m, page(cell = cell))
        assertEquals("الشاشة", plan.step(StepId("panel"))!!.label?.get("ar"))
        assertEquals("Display", plan.step(StepId("panel"))!!.label?.get("en"))
        assertNull(plan.step(StepId.WIFI)!!.label)
        assertNull(SetupPlan.of(manifest(step("panel", false, "panel.theme")), page(cell = cell)).step(StepId("panel"))!!.label)
    }

    @Test
    fun `an integer range reads as whole bounds and a fractional bound is refused`() {
        val half = ConfigKeySpec("panel.half", ConfigKeyType.INTEGER, ConfigValues.Range(0.5, 4.0), ConfigApplies.IMMEDIATELY, page = 7)
        val open = ConfigKeySpec("panel.open", ConfigKeyType.INTEGER, ConfigValues.Range(0.0, null), ConfigApplies.IMMEDIATELY, page = 7)
        val fields = SetupPlan.fieldsOf(manifest(keys = listOf(panelSleep, half, open)), listOf("panel.sleep_s", "panel.half", "panel.open", "panel.unknown"))
        assertEquals(listOf("panel.sleep_s"), fields.map { it.field.key })
        assertEquals(FieldType.IntBetween(0, 600), fields.single().field.type)
    }

    @Test
    fun `a catalog step ignores any keys the manifest lists for it`() {
        val plan = SetupPlan.of(manifest(step("wifi", false, "panel.theme")), page(cell = cell))
        assertEquals(PlanStepKind.CATALOG, plan.step(StepId.WIFI)!!.kind)
        assertEquals(emptyList(), plan.step(StepId.WIFI)!!.fields)
    }

    @Test
    fun `a repeated step id keeps its first place`() {
        val plan = SetupPlan.of(manifest(step("wifi"), step("name"), step("wifi", req = true)), page(cell = cell))
        assertEquals(listOf("wifi", "name"), plan.steps.filter { it.id != StepId.LOCATION }.map { it.id.id })
    }

    @Test
    fun `a manifest of more than 31 steps keeps the first 31 and drops the rest`() {
        val ids = (0 until 40).map { "s$it" }
        val m = manifest(*ids.map { step(it, false, "panel.theme") }.toTypedArray())
        val v = SetupAck.version(ids)
        val plan = SetupPlan.of(m, page(cell = cell, setupV = v, ack = Int.MAX_VALUE))
        val board = plan.steps.filter { it.id != StepId.LOCATION }
        assertEquals(SetupAck.MAX_STEPS, board.size)
        assertEquals((0 until SetupAck.MAX_STEPS).toList(), board.map { it.ackIndex })
        assertEquals(v, plan.version)
        assertFalse(plan.pending)
        assertNull(plan.ackWith(StepId("s31")))
    }

    @Test
    fun `mobility required by the board is satisfied by a known mobility`() {
        val m = manifest(step("mobility", req = true))
        val v = SetupAck.version(listOf("mobility"))
        assertFalse(SetupPlan.of(m, page(mobility = "moving", cell = "", setupV = v, ack = 1)).pending)
        assertTrue(SetupPlan.of(m, page(mobility = "drifting", cell = "", setupV = v, ack = 1)).pending)
    }

    @Test
    fun `only location and mobility can be required and every other catalog step stays optional`() {
        val m = manifest(step("name", req = true), step("site", req = true), step("wifi", req = true), step("lan", req = true), step("mobility", req = true))
        val plan = SetupPlan.of(m, page(cell = cell))
        assertEquals(listOf(true, false, false, false, false, true), plan.steps.map { it.required })
        assertEquals(StepId.LOCATION, plan.steps.first().id)
    }

    @Test
    fun `one ack write can carry several steps of the same list`() {
        val v = crowPanelV
        val plan = SetupPlan.of(crowPanel, page(cell = cell, setupV = v, ack = 0b0001))
        assertEquals(0b1101, plan.ackWith(listOf(StepId.WIFI, StepId("panel"))))
        assertNull(plan.ackWith(listOf(StepId("nope"))))
    }
}
