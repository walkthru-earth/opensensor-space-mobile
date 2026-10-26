package earth.walkthru.opensensor

import androidx.compose.runtime.snapshots.Snapshot
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.opensensor.today.SyncMood
import earth.walkthru.opensensor.today.TodayArchive
import earth.walkthru.opensensor.today.TodayNotice
import earth.walkthru.opensensor.today.syncMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionTodaySourceTest {
    private class Calls {
        val list = mutableListOf<String>()
        val actions = CompanionLinkActions(
            syncFiles = { list += "sync" },
            command = { _, command -> list += command.name },
            connect = { list += "connect $it" },
            disconnect = { list += "disconnect" },
            cancelSync = { list += "cancel $it" },
        )
    }

    private fun TestScope.source(
        state: CompanionState,
        calls: Calls,
        notices: MutableList<TodayNotice>,
        todaySoFar: Flow<TodaySoFar?> = flowOf(null),
        stationFor: (String) -> String? = { null },
    ): CompanionTodaySource {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        state.clock = { 1_000L }
        val source = CompanionTodaySource(scope, state, "device", MutableStateFlow(emptyList()), todaySoFar, calls.actions, stationFor)
        scope.launch { source.notices.collect { notices += it } }
        return source
    }

    private fun connected(status: String? = null): CompanionState = CompanionState().also {
        it.deviceFound("device", "OS-kitchen", -60)
        it.connectionStarted("device")
        assertTrue(it.receiveInfo("{\"proto\":3,\"dev\":\"112233445566\",\"station\":\"st\"}"))
        if (status != null) it.receiveStatus(status)
    }

    private fun settle(scope: TestScope) {
        Snapshot.sendApplyNotifications()
        scope.runCurrent()
    }

    @Test fun mapsTheCompanionStateWithMissingReadingsLeftNull() = runTest {
        val state = connected()
        val source = source(state, Calls(), mutableListOf())
        settle(this)
        val snapshot = source.snapshot.value
        assertEquals("device", snapshot.address)
        assertEquals("OS-kitchen", snapshot.name)
        assertEquals(ConnectionState.Ready(0), snapshot.connection)
        assertEquals(TransportKind.BLE, snapshot.transport)
        assertEquals("st", snapshot.info?.station)
        assertNull(snapshot.status)
        assertNull(snapshot.live)
        assertNull(snapshot.liveAtEpochMs)
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        settle(this)
        assertEquals(12.0, source.snapshot.value.live?.get("pm25"))
        assertEquals(1_000L, source.snapshot.value.liveAtEpochMs)
        state.connectionClosed("gone")
        settle(this)
        assertEquals(ConnectionState.Disconnected, source.snapshot.value.connection)
        assertNull(source.snapshot.value.transport)
    }

    @Test fun knownSensorUsesAuthenticatedLanSnapshotWithoutBluetooth() = runTest {
        val state = CompanionState()
        val calls = Calls()
        val source = source(state, calls, mutableListOf(), stationFor = { "st" })
        state.lanSessionNoticed("OS-kitchen", true, false, CompanionNotice.LAN_CONNECTED, 0, 0, 0, 0.0)
        state.lanInfoReceived("OS-kitchen", "{\"proto\":3,\"dev\":\"112233445566\",\"station\":\"st\"}")
        state.lanStatusReceived("OS-kitchen", "{\"utc\":1,\"clk\":1}")
        state.lanReadingReceived("OS-kitchen", "{\"pms\":4,\"pm25\":12.0}")
        settle(this)

        assertEquals(ConnectionState.Ready(0), source.snapshot.value.connection)
        assertEquals(TransportKind.LAN, source.snapshot.value.transport)
        assertEquals("st", source.snapshot.value.info?.station)
        assertEquals(12.0, source.snapshot.value.live?.get("pm25"))
        source.sync()
        assertEquals(listOf("sync"), calls.list)

        state.lanSessionNoticed("OS-kitchen", false, false, CompanionNotice.LAN_DISCONNECTED, 0, 0, 0, 0.0)
        settle(this)
        assertEquals(ConnectionState.Disconnected, source.snapshot.value.connection)
    }

    @Test fun lanHandshakeIsNotCollectionAndCompletionStaysOnToday() = runTest {
        val state = CompanionState()
        val source = source(state, Calls(), mutableListOf(), stationFor = { "st" })
        state.lanSessionNoticed("OS-kitchen", true, true, CompanionNotice.LAN_CONNECTING, 0, 0, 0, 0.0)
        state.lanInfoReceived("OS-kitchen", "{\"proto\":3,\"dev\":\"112233445566\",\"station\":\"st\"}")
        settle(this)
        assertFalse(source.snapshot.value.sync.running, "a busy handshake is not file collection")

        state.lanSyncStarted("OS-kitchen")
        settle(this)
        assertTrue(source.snapshot.value.sync.running)
        assertTrue(source.snapshot.value.sync.startedAtEpochMs != null)

        state.lanSyncFinished("OS-kitchen", true, SyncResult(SyncEnd.COMPLETE, 1, 1, 0))
        settle(this)
        assertFalse(source.snapshot.value.sync.running)
        assertTrue(source.snapshot.value.sync.finishedAtEpochMs != null)
        state.lanSessionNoticed("OS-kitchen", false, false, CompanionNotice.LAN_DISCONNECTED, 1, 1, 1024, 0.0)
        settle(this)
        assertTrue(source.snapshot.value.sync.finishedAtEpochMs != null, "completion remains visible after the socket closes")
    }

    @Test fun closedLanSyncNeverLooksCaughtUp() = runTest {
        val state = CompanionState()
        val source = source(state, Calls(), mutableListOf(), stationFor = { "st" })
        state.lanSessionNoticed("OS-kitchen", true, false, CompanionNotice.LAN_CONNECTED, 0, 0, 0, 0.0)
        state.lanInfoReceived("OS-kitchen", "{\"proto\":3,\"dev\":\"112233445566\",\"station\":\"st\"}")
        state.lanSyncStarted("OS-kitchen")
        settle(this)
        state.lanSyncStopped("OS-kitchen")
        state.lanSessionNoticed("OS-kitchen", false, false, CompanionNotice.LAN_DISCONNECTED, 0, 0, 0, 0.0)
        settle(this)
        assertFalse(source.snapshot.value.sync.running)
        assertEquals(SyncEndReason.LINK_LOST, source.snapshot.value.sync.endReason, "a closed socket cannot report All caught up")
        assertEquals(SyncMood.FAILED, syncMood(source.snapshot.value.sync, TodayArchive()))

        state.lanSyncFinished("OS-kitchen", false, SyncResult(SyncEnd.BUSY, 0, 0, 0))
        settle(this)
        assertEquals(SyncEndReason.DEVICE_BUSY, source.snapshot.value.sync.endReason, "the engine result remains after session cleanup")
        assertTrue(source.snapshot.value.sync.failures.isEmpty(), "an early end is a reason, not a fake file failure")
    }

    @Test fun anAnchoredClockSyncsFilesOnly() = runTest {
        val calls = Calls()
        val source = source(connected("{\"utc\":1,\"clk\":1}"), calls, mutableListOf())
        source.sync()
        assertEquals(listOf("sync"), calls.list)
    }

    @Test fun allClockQualitiesDelegateMaintenanceToTheSharedRunner() = runTest {
        for (json in listOf("{\"utc\":0,\"clk\":0}", "{\"utc\":1,\"clk\":2}", "{}")) {
            val calls = Calls()
            val notices = mutableListOf<TodayNotice>()
            val source = source(connected(json), calls, notices)
            source.sync()
            assertEquals(listOf("sync"), calls.list)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun explicitClockNoticeWaitsForTheLastNativeWrite() = runTest {
        val state = connected("{\"utc\":0,\"clk\":0}")
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        source.setTime()
        state.today.commandStarted()
        state.today.commandEnded(CommandResult(TodayNotice.ClockSet))
        runCurrent()
        assertEquals(listOf("SET_TIME"), calls.list)
        assertTrue(notices.isEmpty())
        assertTrue(state.today.busy)
        state.today.linkIdle()
        runCurrent()
        assertEquals(listOf<TodayNotice>(TodayNotice.ClockSet), notices)
        assertFalse(state.today.busy)
        state.today.linkIdle()
        runCurrent()
        assertEquals(listOf("SET_TIME"), calls.list)
    }

    @Test fun nothingIsSentWhileACommandOrSyncRuns() = runTest {
        val state = connected()
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        state.today.commandStarted()
        source.flush()
        source.sync()
        state.today.commandFinished(CommandResult(TodayNotice.Finalized(12, 1)))
        state.bluetoothSyncStarted()
        source.setTime()
        runCurrent()
        assertTrue(calls.list.isEmpty())
        assertEquals(
            listOf<TodayNotice>(TodayNotice.Busy, TodayNotice.Busy, TodayNotice.Finalized(12, 1), TodayNotice.Busy),
            notices,
        )
    }

    @Test fun aRefusedLinkReportsBusy() = runTest {
        val state = CompanionState()
        val notices = mutableListOf<TodayNotice>()
        source(state, Calls(), notices)
        state.today.commandRefused()
        runCurrent()
        assertEquals(listOf<TodayNotice>(TodayNotice.Busy), notices)
    }

    @Test fun reconnectAsksForTheSensorTodayWasOpenedFor() = runTest {
        val calls = Calls()
        val source = source(CompanionState(), calls, mutableListOf())
        source.reconnect()
        // No link to close.
        source.disconnect()
        assertEquals(listOf("connect device"), calls.list)
    }

    @Test fun anotherSensorOnTheLinkLeavesThisTodayClosedAndSilent() = runTest {
        val state = connected("{\"utc\":0,\"clk\":0}")
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        val calls = Calls()
        // The other sensor's day, as a station flow that is not guarded would read it.
        val source = source(state, calls, mutableListOf(), flowOf(TodaySoFar(30f, 12, 0.5f)))
        state.connectionStarted("other")
        assertTrue(state.receiveInfo("{\"proto\":3,\"dev\":\"665544332211\",\"station\":\"st2\"}"))
        state.receiveLive("{\"pms\":4,\"pm25\":30.0}")
        settle(this)
        val snapshot = source.snapshot.value
        assertEquals("device", snapshot.address)
        assertEquals(ConnectionState.Disconnected, snapshot.connection)
        assertNull(snapshot.info)
        assertNull(snapshot.live)
        assertNull(snapshot.todaySoFar)
        source.sync()
        source.flush()
        source.disconnect()
        assertTrue(calls.list.isEmpty())
    }

    @Test fun arrivalTimesStayPutWhenTheScreenLooksAgain() = runTest {
        val state = connected()
        var now = 1_000L
        val source = source(state, Calls(), mutableListOf())
        state.clock = { now }
        state.receiveStatus("{\"t\":1}")
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        settle(this)
        assertEquals(1_000L, source.snapshot.value.statusAtEpochMs)
        assertEquals(1_000L, source.snapshot.value.liveAtEpochMs)
        // An unrelated change makes Today look again later. The readings did not arrive again.
        now = 9_000L
        state.bluetoothSyncStarted()
        settle(this)
        assertEquals(1_000L, source.snapshot.value.statusAtEpochMs)
        assertEquals(1_000L, source.snapshot.value.liveAtEpochMs)
        assertEquals(9_000L, source.snapshot.value.sync.startedAtEpochMs)
        now = 12_000L
        state.bluetoothSyncFinished(SyncResult(SyncEnd.COMPLETE, 0, 0, 0))
        settle(this)
        now = 20_000L
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        settle(this)
        assertEquals(12_000L, source.snapshot.value.sync.finishedAtEpochMs)
        assertEquals(20_000L, source.snapshot.value.liveAtEpochMs, "the same value arriving again is a new reading")
    }

    @Test fun aDroppedLinkUnderARunShowsReconnectingWithItsProgress() = runTest {
        val state = connected()
        val source = source(state, Calls(), mutableListOf())
        state.bluetoothSyncStarted()
        state.bluetoothSyncProgressed(SyncProgress(2, 5, 10, 10, 5.0))
        state.bluetoothReconnecting(CompanionNotice.SENSOR_DISCONNECTED)
        settle(this)
        val snapshot = source.snapshot.value
        assertEquals(ConnectionState.Connecting, snapshot.connection)
        assertTrue(snapshot.sync.running)
        assertEquals(2, snapshot.sync.filesDone)
        assertFalse(snapshot.needsPairingRepair)
    }

    @Test fun aLostBondAsksForPairingRepairUntilANewConnection() = runTest {
        val state = connected()
        val source = source(state, Calls(), mutableListOf())
        state.connectionClosedNoticed(CompanionNotice.PAIRING_REMOVED)
        settle(this)
        assertTrue(source.snapshot.value.connection is ConnectionState.Failed)
        assertTrue(source.snapshot.value.needsPairingRepair)
        state.connectionStarted("device")
        settle(this)
        assertEquals(ConnectionState.Connecting, source.snapshot.value.connection)
        assertFalse(source.snapshot.value.needsPairingRepair)
    }
}
