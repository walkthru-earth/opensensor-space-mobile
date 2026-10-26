package space.opensensor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceConfigBridgeTest {
    @Test
    fun restartReopensTheSameSensorWhateverTheCase() {
        assertTrue(shouldReopenAfterRestart("aa-11", "AA-11", collectAllRunning = false))
    }

    @Test
    fun restartReopensAFreeLinkWhenCollectAllIsIdle() {
        assertTrue(shouldReopenAfterRestart("AA-11", null, collectAllRunning = false))
    }

    @Test
    fun restartLeavesAnotherSensorsLink() {
        assertFalse(shouldReopenAfterRestart("AA-11", "BB-22", collectAllRunning = false))
    }

    @Test
    fun restartLeavesAFreeLinkToCollectAll() {
        assertFalse(shouldReopenAfterRestart("AA-11", null, collectAllRunning = true))
    }
}
