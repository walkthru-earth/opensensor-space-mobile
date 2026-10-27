package space.opensensor.today

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardPowerTest {
    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7")
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9")

    private fun stateOf(info: DeviceInfo, json: String): BatteryState? {
        val capabilities = DeviceCapabilities.from(info)
        return BoardPower.of(capabilities.thing).stateOf(capabilities.reading(LiveSample.parse(json)))
    }

    @Test
    fun waveshareReadsBstateFromItsTemplateClasses() {
        val power = BoardPower.of(DeviceCapabilities.from(waveshare).thing)
        assertEquals("battery_percent", power.battery?.name)
        assertEquals("battery_health_state", power.state?.name)
        assertEquals(BatteryState.Kind.LOW, stateOf(waveshare, """{"bstate":3}""")?.kind)
        val external = stateOf(waveshare, """{"bstate":1}""")
        assertEquals(BatteryState.Kind.EXTERNAL_POWER, external?.kind)
        assertTrue(external!!.charging)
        assertFalse(stateOf(waveshare, """{"bstate":2}""")!!.charging)
    }

    @Test
    fun aNodataClassOrAMissingValueShowsNoState() {
        assertNull(stateOf(waveshare, """{"bstate":0}"""))
        assertNull(stateOf(waveshare, """{"pct":50}"""))
    }

    @Test
    fun coreS3ReadsItsChargingStatus() {
        val power = BoardPower.of(DeviceCapabilities.from(cores3).thing)
        assertEquals("charging_status", power.state?.name)
        assertTrue(power.millivolts != null)
    }

    @Test
    fun coreS3MapsChgToChargingAndDischarging() {
        val charging = stateOf(cores3, """{"chg":1}""")
        assertEquals(BatteryState.Kind.CHARGING, charging?.kind)
        assertTrue(charging!!.charging)
        val discharging = stateOf(cores3, """{"chg":0}""")
        assertEquals(BatteryState.Kind.DISCHARGING, discharging?.kind)
        assertFalse(discharging!!.charging)
        assertNull(stateOf(cores3, """{"chg":2}"""), "nodata shows no state")
    }

    @Test
    fun anUnknownClassNameKeepsTheTemplateWord() {
        val state = BatteryState("trickle")
        assertEquals(BatteryState.Kind.OTHER, state.kind)
        assertEquals("trickle", state.name)
    }
}
