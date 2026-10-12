package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeSource(connection: ConnectionState = ConnectionState.Ready(247), status: DeviceStatus? = null) : TodaySource {
    override val snapshot = MutableStateFlow(TodaySnapshot(address = "AA", connection = connection, status = status))
    override val notices = MutableSharedFlow<TodayNotice>(extraBufferCapacity = 8)
    val calls = mutableListOf<String>()

    override fun sync() {
        calls += "sync"
    }

    override fun setTime() {
        calls += "setTime"
    }

    override fun flush() {
        calls += "flush"
    }

    override fun stopSync() {
        calls += "stopSync"
    }

    override fun reconnect() {
        calls += "reconnect"
    }

    override fun disconnect() {
        calls += "disconnect"
    }
}

/** A logger with no UTC anchor, the case where Smart Sync wants this phone's time. */
private val clockMissing = DeviceStatus(utc = 0, clockSource = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayControllerTest {
    private fun TestScope.controller(
        source: FakeSource,
        level: DetailLevel = DetailLevel.SIMPLE,
    ): TodayController {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return TodayController(scope, source, MutableStateFlow(level))
    }

    @Test
    fun flushIsSentOnlyAfterConfirm() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.requestFlush()
        assertEquals(TodayDialog.Flush, c.dialog.value)
        assertEquals(emptyList(), source.calls)
        c.confirm()
        assertEquals(listOf("flush"), source.calls)
    }

    @Test
    fun dismissSendsNothing() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.dismiss()
        c.requestFlush()
        c.dismiss()
        c.confirm()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun commandsWaitForAReadyLink() = runTest {
        val source = FakeSource(connection = ConnectionState.Connecting)
        val c = controller(source)
        c.requestFlush()
        c.smartSync()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun syncNeverAsksAboutTheClock() = runTest {
        for (status in listOf(clockMissing, DeviceStatus(utc = 1, clockSource = 1))) {
            val source = FakeSource(status = status)
            val c = controller(source)
            c.smartSync()
            assertNull(c.dialog.value)
            assertEquals(listOf("sync"), source.calls)
        }
    }

    @Test
    fun aRunningSyncIsNotStartedAgain() = runTest {
        val source = FakeSource()
        source.snapshot.value = source.snapshot.value.copy(sync = SyncProgress(running = true))
        controller(source).smartSync()
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun flushWaitsWhileASyncRunsOrBackgroundWorkHoldsTheDevice() = runTest {
        val source = FakeSource()
        val c = controller(source)
        source.snapshot.value = source.snapshot.value.copy(sync = SyncProgress(running = true))
        c.requestFlush()
        assertNull(c.dialog.value)
        source.snapshot.value = source.snapshot.value.copy(sync = SyncProgress(), heldInBackground = true)
        c.requestFlush()
        c.smartSync()
        assertNull(c.dialog.value)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun stopReachesTheSourceOnlyForThisPhonesRunningSync() = runTest {
        val source = FakeSource()
        val c = controller(source)
        c.stopSync()
        source.snapshot.value = source.snapshot.value.copy(sync = SyncProgress(running = true), heldInBackground = true)
        c.stopSync()
        assertEquals(emptyList(), source.calls)
        source.snapshot.value = source.snapshot.value.copy(heldInBackground = false)
        c.stopSync()
        assertEquals(listOf("stopSync"), source.calls)
    }

    @Test
    fun stateFollowsTheSettingsStore() = runTest {
        val source = FakeSource()
        val level = MutableStateFlow(DetailLevel.SIMPLE)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val c = TodayController(scope, source, level)
        level.value = DetailLevel.DEVELOPER
        assertEquals(DetailLevel.DEVELOPER, c.state.value.level)
    }

    @Test
    fun reconnectAndDisconnectGoToTheSource() = runTest {
        val source = FakeSource(connection = ConnectionState.Disconnected)
        val c = controller(source)
        c.reconnect()
        c.disconnect()
        assertEquals(listOf("reconnect", "disconnect"), source.calls)
    }
}
