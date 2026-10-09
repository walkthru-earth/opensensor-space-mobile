package earth.walkthru.opensensor

import androidx.compose.runtime.snapshots.Snapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.sync.ClockQuality
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.opensensor.today.TodayCommand
import earth.walkthru.opensensor.today.TodayNotice
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
            command = { list += it.name },
            connect = { list += "connect $it" },
            disconnect = { list += "disconnect" },
        )
    }

    private fun TestScope.source(
        state: CompanionState,
        calls: Calls,
        notices: MutableList<TodayNotice>,
        todaySoFar: Flow<TodaySoFar?> = flowOf(null),
    ): CompanionTodaySource {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val source = CompanionTodaySource(scope, state, "device", MutableStateFlow(emptyList()), todaySoFar, calls.actions, now = { 1_000L })
        scope.launch { source.notices.collect { notices += it } }
        return source
    }

    private fun connected(status: String? = null): CompanionState = CompanionState().also {
        it.deviceFound("device", "AQ-kitchen", -60)
        it.connectionStarted("device")
        assertTrue(it.receiveInfo("{\"proto\":2,\"dev\":\"112233445566\",\"station\":\"st\"}"))
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
        assertEquals("AQ-kitchen", snapshot.name)
        assertEquals(ConnectionState.Ready(0), snapshot.connection)
        assertEquals(TransportKind.BLE, snapshot.transport)
        assertEquals("st", snapshot.info?.station)
        assertNull(snapshot.status)
        assertNull(snapshot.live)
        assertNull(snapshot.liveAtEpochMs)
        state.receiveLive("{\"pms\":4,\"pm25\":12.0}")
        settle(this)
        assertEquals(12.0, source.snapshot.value.live?.pm25)
        assertEquals(1_000L, source.snapshot.value.liveAtEpochMs)
        state.connectionClosed("gone")
        settle(this)
        assertEquals(ConnectionState.Disconnected, source.snapshot.value.connection)
        assertNull(source.snapshot.value.transport)
    }

    @Test fun anAnchoredClockSyncsFilesOnly() = runTest {
        val calls = Calls()
        val source = source(connected("{\"utc\":1,\"clk\":1}"), calls, mutableListOf())
        source.sync(ClockSyncMode.AUTOMATIC, clockConfirmed = false)
        assertEquals(listOf("sync"), calls.list)
    }

    @Test fun askModeWantsConsentBeforeTheClockStep() = runTest {
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(connected("{\"utc\":0,\"clk\":0}"), calls, notices)
        source.sync(ClockSyncMode.ASK, clockConfirmed = false)
        assertTrue(calls.list.isEmpty())
        assertEquals(listOf<TodayNotice>(TodayNotice.ClockConfirmationRequired(ClockQuality.MISSING)), notices)
        source.sync(ClockSyncMode.ASK, clockConfirmed = true)
        assertEquals(listOf("SET_TIME"), calls.list)
    }

    @Test fun theClockStepThenTheFileSync() = runTest {
        val state = connected("{\"utc\":0,\"clk\":0}")
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        source.sync(ClockSyncMode.AUTOMATIC, clockConfirmed = false)
        assertEquals(listOf("SET_TIME"), calls.list)
        state.today.commandStarted()
        state.today.commandFinished(CommandResult(TodayNotice.ClockSet))
        runCurrent()
        assertEquals(listOf("SET_TIME", "sync"), calls.list)
        assertEquals(listOf<TodayNotice>(TodayNotice.ClockSet), notices)
        assertFalse(state.today.busy)
    }

    @Test fun theFileSyncWaitsForTheLastWriteOfTheClockStep() = runTest {
        val state = connected("{\"utc\":0,\"clk\":0}")
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        source.sync(ClockSyncMode.AUTOMATIC, clockConfirmed = false)
        state.today.commandStarted()
        // SET_TIME is confirmed, but the status request it queued is still being written.
        state.today.commandEnded(CommandResult(TodayNotice.ClockSet))
        runCurrent()
        assertEquals(listOf("SET_TIME"), calls.list)
        assertTrue(notices.isEmpty())
        assertTrue(state.today.busy)
        state.today.linkIdle()
        runCurrent()
        assertEquals(listOf("SET_TIME", "sync"), calls.list)
        assertEquals(listOf<TodayNotice>(TodayNotice.ClockSet), notices)
        assertFalse(state.today.busy)
        // A second idle link reports nothing more.
        state.today.linkIdle()
        runCurrent()
        assertEquals(listOf("SET_TIME", "sync"), calls.list)
    }

    @Test fun aRefusedClockStepStopsTheSync() = runTest {
        val state = connected("{\"utc\":0,\"clk\":0}")
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        source.sync(ClockSyncMode.AUTOMATIC, clockConfirmed = false)
        state.today.commandStarted()
        state.today.commandFinished(CommandResult(TodayNotice.Rejected(TodayCommand.SET_TIME)))
        runCurrent()
        assertEquals(listOf("SET_TIME"), calls.list)
        // A later confirmed clock set from the card does not start a sync on its own.
        source.setTime()
        state.today.commandFinished(CommandResult(TodayNotice.ClockSet))
        runCurrent()
        assertEquals(listOf("SET_TIME", "SET_TIME"), calls.list)
    }

    @Test fun nothingIsSentWhileACommandOrSyncRuns() = runTest {
        val state = connected()
        val calls = Calls()
        val notices = mutableListOf<TodayNotice>()
        val source = source(state, calls, notices)
        state.today.commandStarted()
        source.flush()
        source.sync(ClockSyncMode.NEVER, clockConfirmed = false)
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
        assertTrue(state.receiveInfo("{\"proto\":2,\"dev\":\"665544332211\",\"station\":\"st2\"}"))
        state.receiveLive("{\"pms\":4,\"pm25\":30.0}")
        settle(this)
        val snapshot = source.snapshot.value
        assertEquals("device", snapshot.address)
        assertEquals(ConnectionState.Disconnected, snapshot.connection)
        assertNull(snapshot.info)
        assertNull(snapshot.live)
        assertNull(snapshot.todaySoFar)
        source.sync(ClockSyncMode.AUTOMATIC, clockConfirmed = true)
        source.flush()
        source.disconnect()
        assertTrue(calls.list.isEmpty())
    }
}
