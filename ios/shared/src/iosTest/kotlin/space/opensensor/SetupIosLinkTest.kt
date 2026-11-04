package space.opensensor

import space.opensensor.setup.SetupLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupIosLinkTest {
    @Test fun oldFirmwareShowsAsUpdateFirmwareOnlyForItsSensor() {
        val state = CompanionState()
        state.connectionStarted("p1")
        assertFalse(state.receiveInfo("{\"proto\":2,\"dev\":\"112233445566\"}"))
        assertEquals(SetupLink.UpdateFirmware, iosSetupLink(state, "P1"))
        assertEquals(SetupLink.Connecting, iosSetupLink(state, "p2"))
        // A firmware notice while opening marks the sensor closed too, and old firmware still wins.
        state.connectionStarted("p1")
        state.connectionClosedNoticed(CompanionNotice.FIRMWARE_UPDATE)
        assertEquals("p1", state.closedId)
        assertEquals(SetupLink.UpdateFirmware, iosSetupLink(state, "p1"))
    }

    @Test fun aConnectThatClosesBeforeInfoShowsAsUnreachableOnlyForItsSensor() {
        val state = CompanionState()
        state.connectionStarted("p1")
        state.connectionClosedNoticed(CompanionNotice.CONNECT_TIMED_OUT)
        assertEquals(SetupLink.Unreachable, iosSetupLink(state, "P1"))
        assertEquals(SetupLink.Connecting, iosSetupLink(state, "p2"))
        state.connectionStarted("p1")
        assertEquals(SetupLink.Connecting, iosSetupLink(state, "p1"), "a new connection clears the closed mark")
    }

    @Test fun aLostBondShowsAsUnreachable() {
        val state = CompanionState()
        state.connectionStarted("p1")
        assertTrue(state.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"thing\":\"st\"}"))
        state.connectionClosedNoticed(CompanionNotice.PAIRING_REMOVED)
        assertEquals(SetupLink.Unreachable, iosSetupLink(state, "p1"))
    }

    @Test fun theSelectedSensorIsReadyOnceItsInfoPassed() {
        val state = CompanionState()
        state.connectionStarted("p1")
        assertEquals(SetupLink.Connecting, iosSetupLink(state, "p1"))
        assertTrue(state.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"thing\":\"st\"}"))
        assertEquals(SetupLink.Ready(3), iosSetupLink(state, "P1"))
        assertEquals(SetupLink.Connecting, iosSetupLink(state, "p2"))
        state.bluetoothPowerChanged(poweredOff = true)
        assertEquals(SetupLink.NoBluetooth, iosSetupLink(state, "p1"))
    }
}
