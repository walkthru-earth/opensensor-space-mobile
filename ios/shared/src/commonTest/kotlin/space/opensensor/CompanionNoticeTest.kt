package space.opensensor

import space.opensensor.strings.UiText
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.companion_ble_off
import space.opensensor.strings.resources.companion_firmware_update_unknown
import space.opensensor.strings.resources.companion_lan_syncing
import space.opensensor.strings.resources.msg_busy_before_sync
import space.opensensor.strings.resources.msg_hardware_in_use
import space.opensensor.strings.resources.msg_verification_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionNoticeTest {
    @Test fun everyNoticeUsesACatalogueString() {
        for (notice in CompanionNotice.entries) assertTrue(notice.text is UiText.Resource, notice.name)
        assertEquals(UiText.of(Res.string.msg_verification_failed), CompanionNotice.VERIFICATION_FAILED.text)
        assertEquals(UiText.of(Res.string.msg_busy_before_sync), CompanionNotice.BUSY_BEFORE_SYNC.text)
        assertEquals(UiText.of(Res.string.companion_firmware_update_unknown, 3), CompanionNotice.FIRMWARE_UPDATE.text)
    }

    @Test fun lanNoticeKeepsTheCardCountsAndStopsTheRate() {
        val state = CompanionState()
        state.lanSessionChanged("a", true, true, "syncing", 2, 4, 4096, 1024.0)
        state.lanSessionNoticed("a", false, false, CompanionNotice.HARDWARE_IN_USE, 2, 4, 4096, 1024.0)
        val session = state.lanSessions.getValue("a")
        assertEquals(2, session.filesDone)
        assertEquals(4096L, session.bytesDone)
        assertEquals(0.0, session.bytesPerSecond)
        assertFalse(session.connected)
        assertEquals(UiText.of(Res.string.msg_hardware_in_use), session.message)
    }

    @Test fun noNoticeNamesWifiAccessOrTheToken() {
        // Wi-Fi access is fetched, renewed and dropped quietly, so no link notice is about it.
        val quiet = CompanionNotice.entries.map { it.name }.filter { "TOKEN" in it || "WIFI_ACCESS" in it || "ENROLL" in it }
        assertEquals(emptyList(), quiet)
    }

    @Test fun aClosedConnectionShowsItsNotice() {
        val state = CompanionState()
        state.connectionStarted("device")
        state.connectionClosedNoticed(CompanionNotice.VERIFICATION_FAILED)
        assertFalse(state.connected)
        assertEquals(UiText.of(Res.string.msg_verification_failed), state.message)
    }

    @Test fun aSyncingNoticeKeepsTheRate() {
        val state = CompanionState()
        state.lanSessionNoticed("a", true, true, CompanionNotice.LAN_SYNCING, 1, 4, 512, 2048.0)
        val session = state.lanSessions.getValue("a")
        assertEquals(2048.0, session.bytesPerSecond)
        assertEquals(UiText.of(Res.string.companion_lan_syncing), session.message)
    }

    @Test fun bluetoothNoticesCloseTheConnectionAndClearWhenOn() {
        val state = CompanionState()
        state.connectionStarted("device")
        state.bluetoothNoticed(false, CompanionNotice.BLUETOOTH_OFF)
        assertFalse(state.bluetoothAvailable)
        assertEquals(null, state.selectedId)
        assertEquals(UiText.of(Res.string.companion_ble_off), state.message)
        state.bluetoothNoticed(true, null)
        assertTrue(state.bluetoothAvailable)
        assertEquals(UiText.None, state.message)
    }

    @Test fun aWifiAccessRequestKeepsOnlyWhetherItStored() {
        val state = CompanionState()
        state.wifiAccessEnded(stored = true)
        assertTrue(state.wifiAccessStored)
        state.wifiAccessSaving()
        assertTrue(state.wifiAccessBusy)
        assertFalse(state.wifiAccessStored)
        state.wifiAccessEnded(stored = false)
        assertFalse(state.wifiAccessBusy)
        assertFalse(state.wifiAccessStored)
        state.wifiAccessEnded(stored = true)
        state.wifiAccessReset()
        assertFalse(state.wifiAccessBusy)
        assertFalse(state.wifiAccessStored)
        assertEquals(UiText.None, state.message)
    }
}
