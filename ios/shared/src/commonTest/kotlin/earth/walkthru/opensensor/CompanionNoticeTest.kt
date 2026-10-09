package earth.walkthru.opensensor

import earth.walkthru.opensensor.strings.UiText
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.companion_lan_needs_access
import earth.walkthru.opensensor.strings.resources.msg_busy_before_sync
import earth.walkthru.opensensor.strings.resources.msg_hardware_in_use
import earth.walkthru.opensensor.strings.resources.msg_identity_enroll_ble
import earth.walkthru.opensensor.strings.resources.msg_lan_token_stored
import earth.walkthru.opensensor.strings.resources.msg_rejected_get_token
import earth.walkthru.opensensor.strings.resources.msg_verification_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionNoticeTest {
    @Test fun everyNoticeUsesACatalogueString() {
        for (notice in CompanionNotice.entries) assertTrue(notice.text is UiText.Resource, notice.name)
        assertEquals(UiText.of(Res.string.msg_identity_enroll_ble), CompanionNotice.ENROLL_OVER_BLUETOOTH.text)
        assertEquals(UiText.of(Res.string.msg_verification_failed), CompanionNotice.VERIFICATION_FAILED.text)
        assertEquals(UiText.of(Res.string.msg_busy_before_sync), CompanionNotice.BUSY_BEFORE_SYNC.text)
    }

    @Test fun lanNoticeKeepsTheCardCountsAndStopsTheRate() {
        val state = CompanionState()
        state.lanSessionChanged("a", true, true, "syncing", 2, 4, 4096, 1024.0)
        state.lanSessionNoticed("a", false, false, CompanionNotice.HARDWARE_IN_USE, 2, 4, 4096)
        val session = state.lanSessions.getValue("a")
        assertEquals(2, session.filesDone)
        assertEquals(4096L, session.bytesDone)
        assertEquals(0.0, session.bytesPerSecond)
        assertFalse(session.connected)
        assertEquals(UiText.of(Res.string.msg_hardware_in_use), session.message)
    }

    @Test fun wifiAccessNoticesShowTheAndroidWords() {
        val state = CompanionState()
        state.wifiAccessNoticed(true, false, CompanionNotice.SAVE_WIFI_ACCESS)
        assertEquals(UiText.of(Res.string.companion_lan_needs_access), state.wifiAccessMessage)
        state.wifiAccessNoticed(true, false, CompanionNotice.TOKEN_STORED)
        assertEquals(UiText.of(Res.string.msg_lan_token_stored), state.wifiAccessMessage)
        state.wifiAccessNoticed(true, false, CompanionNotice.TOKEN_REFUSED)
        assertEquals(UiText.of(Res.string.msg_rejected_get_token), state.wifiAccessMessage)
        assertTrue(state.wifiAccessAvailable)
        assertFalse(state.wifiAccessBusy)
    }

    @Test fun aClosedConnectionShowsItsNotice() {
        val state = CompanionState()
        state.connectionStarted("device")
        state.connectionClosedNoticed(CompanionNotice.VERIFICATION_FAILED)
        assertFalse(state.connected)
        assertEquals(UiText.of(Res.string.msg_verification_failed), state.message)
    }
}
