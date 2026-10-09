package earth.walkthru.opensensor

import earth.walkthru.opensensor.strings.UiText
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.companion_sync_storage_failed
import earth.walkthru.opensensor.strings.resources.msg_busy
import earth.walkthru.opensensor.strings.resources.msg_hardware_in_use
import earth.walkthru.opensensor.strings.resources.msg_sync_aborted
import earth.walkthru.opensensor.strings.resources.msg_sync_finished
import earth.walkthru.opensensor.strings.resources.msg_sync_stopped
import earth.walkthru.opensensor.strings.resources.msg_token_needs_ble
import earth.walkthru.opensensor.strings.resources.msg_up_to_date
import earth.walkthru.opensensor.strings.resources.sync_some_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncOutcomeTest {
    @Test fun outcomesUseTheAndroidKeys() {
        assertEquals(UiText.of(Res.string.msg_up_to_date), SyncResult(SyncEnd.COMPLETE, 3, 0, 0).message)
        assertEquals(UiText.plural(Res.plurals.msg_sync_finished, 2, 2, 2), SyncResult(SyncEnd.COMPLETE, 3, 2, 0).message)
        assertEquals(UiText.plural(Res.plurals.sync_some_failed, 1, 1), SyncResult(SyncEnd.FILES_NOT_SAVED, 3, 2, 1).message)
        assertEquals(UiText.of(Res.string.msg_sync_stopped), SyncResult(SyncEnd.STOPPED, 3, 0, 0).message)
        assertEquals(UiText.of(Res.string.msg_token_needs_ble), SyncResult(SyncEnd.AUTH_FAILED, 0, 0, 0).message)
        assertEquals(UiText.of(Res.string.msg_busy), SyncResult(SyncEnd.BUSY, 0, 0, 0).message)
        assertEquals(UiText.of(Res.string.msg_hardware_in_use), SyncResult(SyncEnd.IN_USE, 0, 0, 0).message)
        assertEquals(UiText.of(Res.string.msg_sync_aborted), SyncResult(SyncEnd.LINK_LOST, 3, 0, 0).message)
        assertEquals(UiText.of(Res.string.msg_sync_aborted), SyncResult(SyncEnd.FAILED, 3, 0, 0).message)
        assertEquals(UiText.of(Res.string.companion_sync_storage_failed), SyncResult(SyncEnd.STORAGE_FAILED, 3, 2, 1).message)
    }

    @Test fun onlyAUsableLinkStaysOpenAndOnlyALostLinkResumes() {
        // A storage or protocol fault leaves the link usable. A lost link, a refused token or a busy sensor does not.
        val dropsLink = setOf(SyncEnd.STOPPED, SyncEnd.LINK_LOST, SyncEnd.AUTH_FAILED, SyncEnd.BUSY)
        for (end in SyncEnd.entries) {
            val result = SyncResult(end, 0, 0, 0)
            assertEquals(end !in dropsLink, result.keepsLink, end.name)
            assertEquals(end == SyncEnd.LINK_LOST, result.resumable)
        }
    }

    @Test fun lanSyncFinishedKeepsTheCountsAndShowsTheOutcome() {
        val state = CompanionState()
        state.lanSessionChanged("a", true, true, "syncing", 2, 4, 4096, 1024.0)
        state.lanSyncFinished("a", true, SyncResult(SyncEnd.COMPLETE, 4, 2, 0))
        val session = state.lanSessions.getValue("a")
        assertEquals(2, session.filesDone)
        assertEquals(4, session.filesTotal)
        assertEquals(4096L, session.bytesDone)
        assertEquals(0.0, session.bytesPerSecond)
        assertFalse(session.busy)
        assertEquals(UiText.plural(Res.plurals.msg_sync_finished, 2, 2, 2), session.message)
    }

    @Test fun bluetoothSyncNeedsAConnectionAndEndsWithItsOutcome() {
        val state = CompanionState()
        state.bluetoothSyncAvailable(true)
        assertFalse(state.syncAvailable)
        state.connectionStarted("device")
        assertTrue(state.receiveInfo("{\"proto\":1,\"dev\":\"112233445566\"}"))
        state.bluetoothSyncAvailable(true)
        assertTrue(state.syncAvailable)

        state.bluetoothSyncStarted()
        assertTrue(state.syncBusy)
        state.bluetoothSyncProgressed(SyncProgress(1, 3, 10, 10, 5.0))
        assertEquals(1, state.syncProgress?.filesDone)
        state.bluetoothSyncFinished(SyncResult(SyncEnd.STOPPED, 3, 0, 0))
        assertFalse(state.syncBusy)
        assertEquals(UiText.of(Res.string.msg_sync_stopped), state.syncMessage)
        // A late update never brings back a finished sync.
        state.bluetoothSyncProgressed(SyncProgress(2, 3, 10, 10, 5.0))
        assertEquals(1, state.syncProgress?.filesDone)
    }

    @Test fun aLostBluetoothSyncClosesTheConnectionWithItsOutcome() {
        val state = CompanionState()
        state.connectionStarted("device")
        assertTrue(state.receiveInfo("{\"proto\":1,\"dev\":\"112233445566\"}"))
        state.bluetoothSyncAvailable(true)
        state.bluetoothSyncStarted()
        state.bluetoothSyncClosed(SyncResult(SyncEnd.LINK_LOST, 3, 0, 0))
        assertFalse(state.connected)
        assertFalse(state.syncAvailable)
        assertFalse(state.syncBusy)
        assertNull(state.syncProgress)
        assertEquals(UiText.of(Res.string.msg_sync_aborted), state.message)
    }
}
