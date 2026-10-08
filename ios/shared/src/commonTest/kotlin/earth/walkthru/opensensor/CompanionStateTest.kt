package earth.walkthru.opensensor

import earth.walkthru.aqsync.data.history.NowReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionStateTest {
    @Test fun lanResultsOnlyApplyToActiveDiscoveryAndAreDeduplicated() {
        val state = CompanionState()
        state.lanScanStarted()
        state.lanDevicesFound(listOf("AQ-b", "AQ-a", "AQ-a"))
        assertEquals(listOf("AQ-a", "AQ-b"), state.lanDevices)
        state.lanScanStopped()
        state.lanDevicesFound(listOf("late result"))
        assertEquals(listOf("AQ-a", "AQ-b"), state.lanDevices)
        state.lanScanStarted()
        assertTrue(state.lanDevices.isEmpty())
    }

    @Test fun waitingForLanPermissionKeepsDiscoveryAliveAndDoesNotClearBle() {
        val state = connected()
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        state.lanScanStarted()
        state.lanScanWaiting()
        assertTrue(state.lanScanning)
        state.lanScanReady()
        state.lanDevicesFound(listOf("AQ-a"))
        assertEquals(listOf("AQ-a"), state.lanDevices)
        state.lanScanUnavailable()
        assertFalse(state.lanScanning)
        assertTrue(state.connected)
        assertIs<NowReading.Reading>(state.reading)
    }

    private fun connected(): CompanionState = CompanionState().also {
        it.connectionStarted("device")
        assertTrue(it.receiveInfo("{\"proto\":2,\"dev\":\"112233445566\"}"))
    }

    @Test fun malformedOrExpiredReadingClearsPreviousValue() {
        val state = connected()
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        assertIs<NowReading.Reading>(state.reading)
        state.receiveLive("bad JSON")
        assertEquals(NowReading.Waiting, state.reading)
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        state.readingExpired()
        assertEquals(NowReading.Waiting, state.reading)
    }

    @Test fun disconnectAndRadioLossClearDeviceAndValues() {
        val state = connected()
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        state.bluetoothChanged(false, "Bluetooth is off")
        assertNull(state.selectedId)
        assertNull(state.live)
        assertNull(state.info)
        assertFalse(state.connected)
        // Late notifications must not resurrect a closed session.
        state.receiveLive("{\"pms\":4,\"pm25\":99.0}")
        assertNull(state.live)
    }

    @Test fun unsupportedInfoNeverOpensAReadingSession() {
        val state = CompanionState()
        state.connectionStarted("device")
        assertFalse(state.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\"}"))
        assertFalse(state.connected)
        assertFalse(state.receiveInfo("{}"))
    }

    @Test fun duplicateAdvertisementsUpdateOneDevice() {
        val state = CompanionState()
        state.scanStarted()
        state.deviceFound("a", "AQ-a", -80)
        state.deviceFound("b", "AQ-b", -70)
        state.deviceFound("a", "AQ-a", -60)
        assertEquals(2, state.devices.size)
        assertEquals("a", state.devices.first().id)
        assertEquals(-60, state.devices.first().rssi)
        state.scanStarted()
        assertTrue(state.devices.isEmpty())
    }
}
