package space.opensensor.data.lan

import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.LanConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanAutoEnableTest {
    @Test
    fun onlyAnExplicitOffBelowDeveloperIsSwitchedBackOn() {
        val off = DeviceConfig(lan = LanConfig(on = 0))
        assertTrue(LanAutoEnable.shouldEnable(off, developer = false))
        assertFalse(LanAutoEnable.shouldEnable(off, developer = true), "Developer keeps the switch")
        assertFalse(LanAutoEnable.shouldEnable(DeviceConfig(lan = LanConfig(on = 1)), developer = false))
        assertFalse(LanAutoEnable.shouldEnable(DeviceConfig(), developer = false), "old firmware without lan")
        assertFalse(LanAutoEnable.shouldEnable(null, developer = false))
    }
}
