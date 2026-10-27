package space.opensensor

import space.opensensor.collection.SyncEndReason
import space.opensensor.family.air.NowReading
import space.opensensor.permissions.AccessStatus
import space.opensensor.permissions.PermissionFlow
import space.opensensor.strings.UiText
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.companion_firmware_update
import space.opensensor.strings.resources.companion_firmware_update_unknown
import space.opensensor.strings.resources.companion_info_invalid
import space.opensensor.strings.resources.companion_lan_denied
import space.opensensor.strings.resources.companion_lan_waiting
import space.opensensor.strings.resources.companion_live
import space.opensensor.strings.resources.companion_live_unreadable
import space.opensensor.strings.resources.now_waiting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionStateTest {
    /** The bundled Waveshare dictionary, so LIVE has a thing to read through. */
    private val dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809"

    private fun lanConnected(state: CompanionState, name: String, thing: String = "thing-$name") {
        state.lanSessionChanged(name, true, false, "connected", 0, 0, 0)
        state.lanInfoReceived(name, "{\"proto\":3,\"thing\":\"$thing\",\"dev\":\"112233445566\",\"dict\":\"$dict\"}")
    }

    @Test fun pausedCollectionRetainsProgressWithoutShowingAStaleTransferRate() {
        val state = CompanionState()
        state.lanSessionChanged("a", true, true, "syncing", 2, 4, 16384, 2048.0)
        state.lanCollectionPaused("a", true)
        state.lanSessionChanged("a", false, false, "Paused until you return", 2, 4, 16384, 2048.0)
        val session = state.lanSessions.getValue("a")
        assertEquals(2, session.filesDone)
        assertEquals(4, session.filesTotal)
        assertEquals(16384L, session.bytesDone)
        assertEquals(0.0, session.bytesPerSecond)
        assertFalse(session.busy)
        assertFalse(session.connected)
        assertTrue("a" in state.lanPaused)
        state.lanCollectionPaused("b", true)
        state.lanCollectionPaused("a", false)
        assertFalse("a" in state.lanPaused)
        assertTrue("b" in state.lanPaused)
        assertEquals(2, state.lanSessions.getValue("a").filesDone)
    }

    @Test fun lanTransferRatesAreIndependentAndClearWhenCollectionStops() {
        val state = CompanionState()
        state.lanSessionChanged("a", true, true, "syncing", 0, 4, 100, 2048.0)
        state.lanSessionChanged("b", true, true, "syncing", 0, 2, 10, 1024.0)
        assertEquals(2048.0, state.lanSessions.getValue("a").bytesPerSecond)
        assertEquals(1024.0, state.lanSessions.getValue("b").bytesPerSecond)
        state.lanSessionChanged("a", false, false, "disconnected", 0, 0, 0, 2048.0)
        assertEquals(0.0, state.lanSessions.getValue("a").bytesPerSecond)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            state.lanSessionChanged("b", true, true, "syncing", 0, 2, 10, invalid)
            assertEquals(0.0, state.lanSessions.getValue("b").bytesPerSecond)
        }
        state.lanSessionChanged("b", true, false, "complete", 2, 2, 0, 1024.0)
        assertEquals(0.0, state.lanSessions.getValue("b").bytesPerSecond)
    }

    @Test fun lanResultsOnlyApplyToActiveDiscoveryAndAreDeduplicated() {
        val state = CompanionState()
        state.lanScanStarted()
        state.lanDevicesFound(listOf("OS-b", "OS-a", "OS-a"))
        assertEquals(listOf("OS-a", "OS-b"), state.lanDevices)
        state.lanScanStopped()
        state.lanDevicesFound(listOf("late result"))
        assertEquals(listOf("OS-a", "OS-b"), state.lanDevices)
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
        state.lanDevicesFound(listOf("OS-a"))
        assertEquals(listOf("OS-a"), state.lanDevices)
        state.lanScanUnavailable()
        assertFalse(state.lanScanning)
        assertTrue(state.connected)
        assertIs<NowReading.Reading>(state.reading)
    }

    @Test fun localNetworkDenialIsAnExplicitHintThatClearsWhenAccessReturns() {
        val state = CompanionState()
        state.lanScanAccessDenied()
        assertFalse(state.lanAccessDenied)
        state.lanScanStarted()
        state.lanScanWaiting()
        assertFalse(state.lanAccessDenied)
        assertEquals(UiText.of(Res.string.companion_lan_waiting), state.lanMessage)
        state.lanScanAccessDenied()
        assertTrue(state.lanAccessDenied)
        assertTrue(state.lanScanning)
        assertEquals(UiText.of(Res.string.companion_lan_denied), state.lanMessage)
        state.lanScanReady()
        assertFalse(state.lanAccessDenied)
        state.lanScanAccessDenied()
        state.lanScanStarted()
        assertFalse(state.lanAccessDenied)
    }

    @Test fun independentLanSessionsAndLiveReadingsNeverOverwriteEachOther() {
        val state = CompanionState()
        lanConnected(state, "a")
        lanConnected(state, "b")
        state.lanSessionChanged("a", true, true, "syncing", 1, 4, 100)
        state.lanSessionChanged("b", true, false, "ready", 0, 0, 0)
        state.lanReadingReceived("a", "{\"pms\":4,\"pm25\":12.0}")
        state.lanReadingReceived("b", "{\"pms\":4,\"pm25\":22.0}")
        assertTrue(state.lanSessions.getValue("a").busy)
        assertFalse(state.lanSessions.getValue("b").busy)
        assertEquals(12.0, (state.lanReadings.getValue("a") as NowReading.Reading).primary)
        state.lanReadingExpired("a")
        assertFalse("a" in state.lanReadings)
        assertEquals(22.0, (state.lanReadings.getValue("b") as NowReading.Reading).primary)
        state.lanReadingReceived("b", "bad JSON")
        assertEquals(NowReading.Waiting, state.lanReadings["b"])
    }

    private fun connected(): CompanionState = CompanionState().also {
        it.connectionStarted("device")
        assertTrue(it.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"dict\":\"$dict\"}"))
    }

    @Test fun disconnectedLanSensorCannotKeepOrRestoreAnOldReading() {
        val state = CompanionState()
        lanConnected(state, "a")
        state.lanReadingReceived("a", "{\"pms\":4,\"pm25\":12.0}")
        assertIs<NowReading.Reading>(state.lanReadings["a"])
        state.lanSessionChanged("a", false, false, "disconnected", 0, 0, 0)
        assertFalse("a" in state.lanReadings)
        state.lanReadingReceived("a", "{\"pms\":4,\"pm25\":99.0}")
        assertFalse("a" in state.lanReadings)
        state.lanSessionChanged("a", true, false, "reconnected", 0, 0, 0)
        assertFalse("a" in state.lanReadings)
    }

    @Test fun lanTodaySnapshotBelongsOnlyToConnectedThingAndClearsOnClose() {
        val state = CompanionState()
        lanConnected(state, "kitchen", thing = "thing-a")
        state.lanStatusReceived("kitchen", "{\"utc\":1,\"clk\":1}")
        state.lanReadingReceived("kitchen", "{\"pms\":4,\"pm25\":12.0}")
        assertEquals("kitchen", state.connectedLanForThing("thing-a"))
        assertNull(state.connectedLanForThing("thing-b"))
        assertEquals("112233445566", state.lanInfo["kitchen"]?.dev)
        assertEquals(12.0, state.lanLive["kitchen"]?.get("pm25"))
        state.lanSessionChanged("kitchen", false, false, "disconnected", 0, 0, 0)
        assertNull(state.connectedLanForThing("thing-a"))
        assertFalse("kitchen" in state.lanInfo)
        assertFalse("kitchen" in state.lanStatus)
        assertFalse("kitchen" in state.lanLive)
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
        assertEquals(UiText.of(Res.string.now_waiting), state.message)
    }

    @Test fun anOverflowSampleClearsTheLiveValues() {
        val state = connected()
        state.receiveLive("{\"seq\":1,\"pms\":4,\"pm25\":12.0}")
        assertIs<NowReading.Reading>(state.reading)
        state.receiveLive("{\"seq\":null,\"mono\":null,\"utc\":null,\"pms\":null,\"pm25\":null}")
        assertTrue(state.live?.overflow == true)
        assertNull(state.live?.get("pm25"))
        assertEquals(NowReading.Waiting, state.reading)
    }

    @Test fun infoWithAnUnknownDictionaryHasNoLiveReading() {
        val state = CompanionState()
        state.connectionStarted("device")
        assertTrue(state.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"dict\":\"${"0".repeat(64)}\"}"))
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        assertEquals(NowReading.Waiting, state.reading)
    }

    @Test fun messagesAreCatalogueKeysAndAdapterWordsPassThrough() {
        val state = connected()
        assertEquals(UiText.of(Res.string.companion_live), state.message)
        state.receiveLive("bad JSON")
        assertEquals(UiText.of(Res.string.companion_live_unreadable), state.message)
        state.bluetoothChanged(false, "Bluetooth is off")
        assertEquals(UiText.Plain("Bluetooth is off"), state.message)
        state.connectionStarted("device")
        assertFalse(state.receiveInfo("{}"))
        assertEquals(UiText.of(Res.string.companion_info_invalid), state.message)
    }

    @Test fun warmingCopyMatchesAndroid() {
        // Same words as the shared Today pms_warming_hint.
        val state = connected()
        state.receiveLive("{\"pms\":1}")
        val warming = state.reading
        assertEquals("The sensor is warming up. It takes about a minute after power on.", (warming as NowReading.SensorNotReady).hint)
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
        assertFalse(state.receiveInfo("{\"proto\":2,\"dev\":\"112233445566\"}"))
        assertFalse(state.connected)
        assertEquals(UiText.of(Res.string.companion_firmware_update, 2, 3), state.message)
        state.connectionStarted("device")
        assertFalse(state.receiveInfo("{\"dev\":\"112233445566\"}"))
        assertEquals(UiText.of(Res.string.companion_firmware_update_unknown, 3), state.message)
        assertFalse(state.receiveInfo("{}"))
    }

    @Test fun newerProtocolStillOpensAReadingSession() {
        val state = CompanionState()
        state.connectionStarted("device")
        assertTrue(state.receiveInfo("{\"proto\":4,\"dev\":\"112233445566\"}"))
    }

    @Test fun duplicateAdvertisementsUpdateOneDevice() {
        val state = CompanionState()
        state.scanStarted()
        state.deviceFound("a", "OS-a", -80)
        state.deviceFound("b", "OS-b", -70)
        state.deviceFound("a", "OS-a", -60)
        assertEquals(2, state.devices.size)
        assertEquals("a", state.devices.first().id)
        assertEquals(-60, state.devices.first().rssi)
        state.scanStarted()
        assertTrue(state.devices.isEmpty())
    }

    @Test fun settingsLinksFollowTheReportedDenials() {
        val state = CompanionState()
        // Nothing is known, and no link is offered, before the first tap.
        assertEquals(AccessStatus.NOT_DETERMINED, state.bluetoothAccess)
        assertEquals(AccessStatus.UNKNOWN, state.lanAccess)
        assertFalse(PermissionFlow.offersSettings(state.bluetoothAccess))
        assertFalse(PermissionFlow.offersSettings(state.lanAccess))

        state.bluetoothAuthorizationChanged(2) // CBManagerAuthorization.denied
        assertTrue(PermissionFlow.offersSettings(state.bluetoothAccess))
        state.bluetoothAuthorizationChanged(1) // restricted, nothing the user can change
        assertFalse(PermissionFlow.offersSettings(state.bluetoothAccess))
        state.bluetoothAuthorizationChanged(3)
        assertEquals(AccessStatus.GRANTED, state.bluetoothAccess)

        state.lanScanStarted()
        state.lanScanWaiting()
        assertFalse(PermissionFlow.offersSettings(state.lanAccess))
        state.lanScanAccessDenied()
        assertTrue(PermissionFlow.offersSettings(state.lanAccess))
        state.lanScanReady()
        assertEquals(AccessStatus.UNKNOWN, state.lanAccess)
    }

    private fun linked(id: String = "p1"): CompanionState = CompanionState().also {
        it.connectionStarted(id)
        assertTrue(it.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"thing\":\"st\"}"))
    }

    @Test fun aDroppedLinkUnderARunKeepsItsProgressUntilTheSensorAnswersAgain() {
        val state = linked()
        state.bluetoothSyncStarted()
        state.bluetoothSyncProgressed(SyncProgress(1, 3, 10, 10, 5.0))
        state.bluetoothReconnecting(CompanionNotice.SENSOR_DISCONNECTED)
        assertNull(state.selectedId)
        assertEquals("p1", state.reconnectingId)
        assertTrue(state.syncBusy)
        assertEquals(1, state.syncProgress?.filesDone)
        state.connectionStarted("p1")
        assertEquals("p1", state.reconnectingId, "dialling the same sensor again is still a reconnect")
        assertTrue(state.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"thing\":\"st\"}"))
        assertNull(state.reconnectingId)
        state.bluetoothSyncFinished(SyncResult(SyncEnd.COMPLETE, 3, 3, 0))
        assertFalse(state.syncBusy)
    }

    @Test fun aLostBondMarksTheSensorFailedUntilANewConnectionOrForget() {
        val state = linked()
        state.bluetoothSyncStarted()
        state.bluetoothSyncClosed(SyncResult(SyncEnd.AUTH_FAILED, 0, 0, 0, endReason = SyncEndReason.PAIRING_REPAIR))
        assertEquals("p1", state.failedId)
        state.connectionStarted("p1")
        assertNull(state.failedId)
        val other = linked()
        other.connectionClosedNoticed(CompanionNotice.PAIRING_REMOVED)
        assertEquals("p1", other.failedId)
        other.sensorForgotten("P1")
        assertNull(other.failedId)
        val plain = linked()
        plain.bluetoothSyncClosed(SyncResult(SyncEnd.LINK_LOST, 0, 0, 0))
        assertNull(plain.failedId, "an ordinary loss needs no repair")
    }

    @Test fun oldFirmwareMarksTheSensorOutdatedUntilANewConnection() {
        val state = CompanionState()
        state.connectionStarted("p1")
        state.connectionClosedNoticed(CompanionNotice.FIRMWARE_UPDATE)
        assertEquals("p1", state.outdatedId)
        assertNull(state.failedId)
        state.connectionStarted("p1")
        assertNull(state.outdatedId)
    }

    @Test fun arrivalTimesComeFromTheMessagesNotFromLooking() {
        val state = linked()
        var now = 5L
        state.clock = { now }
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        now = 50L
        assertEquals(5L, state.liveAtEpochMs)
        state.readingExpired()
        assertNull(state.liveAtEpochMs)
        state.bluetoothSyncStarted()
        assertEquals(50L, state.syncStartedAtEpochMs)
        assertNull(state.syncFinishedAtEpochMs)
    }

    @Test fun backgroundOwnershipFollowsTheAdapters() {
        val state = CompanionState()
        state.bluetoothBackgroundOwned(true)
        state.lanBackgroundOwned("aq-b", true)
        assertTrue(state.bluetoothBackground)
        assertEquals(setOf("aq-b"), state.lanBackground)
        state.bluetoothBackgroundOwned(false)
        state.lanBackgroundOwned("aq-b", false)
        assertFalse(state.bluetoothBackground)
        assertTrue(state.lanBackground.isEmpty())
    }

    @Test fun aBackgroundHoldMarksOnlyTheSensorItHolds() {
        val state = CompanionState()
        state.connectionStarted("ble-a")
        state.bluetoothBackgroundOwned(true)
        assertTrue(state.heldInBackground("ble-a", null))
        assertFalse(state.heldInBackground("ble-b", null))
        state.lanScanStarted()
        state.lanSensorsFound(listOf("aq-b"), listOf("thing-b"))
        state.lanBackgroundOwned("aq-b", true)
        assertTrue(state.heldInBackground("ble-b", "thing-b"))
        assertFalse(state.heldInBackground("ble-c", "thing-c"))
    }
}
