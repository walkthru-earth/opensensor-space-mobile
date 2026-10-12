package earth.walkthru.aqsync.ui.files

import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceOwnership
import earth.walkthru.aqsync.data.DeviceSnapshot
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.anyCollectionActive
import earth.walkthru.aqsync.data.background.AutoSyncScheduler.Enqueue
import earth.walkthru.aqsync.data.ble.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncRequestTest {
    private var released = 0
    private var enqueued = 0

    private suspend fun request(ownership: DeviceOwnership, releases: Boolean = true, result: Enqueue = Enqueue.QUEUED) = backgroundSyncRequest(
        ownership,
        release = { released++; releases },
        enqueue = { enqueued++; result },
    )

    @Test
    fun `a running interactive sync keeps running and nothing is queued`() = runTest {
        assertEquals(R.string.files_bg_keeps_running, request(DeviceOwnership.INTERACTIVE))
        assertEquals(0, released)
        assertEquals(0, enqueued)
    }

    @Test
    fun `a worker already holding the device counts as queued`() = runTest {
        assertEquals(R.string.files_bg_already, request(DeviceOwnership.BACKGROUND))
        assertEquals(0, released)
        assertEquals(0, enqueued)
    }

    @Test
    fun `an idle device is released before the request is queued`() = runTest {
        assertEquals(R.string.files_bg_event, request(DeviceOwnership.IDLE))
        assertEquals(1, released)
        assertEquals(1, enqueued)
    }

    @Test
    fun `a failed release queues nothing`() = runTest {
        assertEquals(R.string.msg_busy, request(DeviceOwnership.IDLE, releases = false))
        assertEquals(0, enqueued)
    }

    @Test
    fun `a request already waiting is reported honestly`() = runTest {
        assertEquals(R.string.files_bg_already, request(DeviceOwnership.IDLE, result = Enqueue.ALREADY_QUEUED))
    }

    @Test
    fun `the Files flag follows this device's request or a worker holding it`() = runTest {
        assertFalse(backgroundActive(flowOf(false), flowOf(DeviceOwnership.IDLE)).first())
        assertFalse(backgroundActive(flowOf(false), flowOf(DeviceOwnership.INTERACTIVE)).first())
        assertTrue(backgroundActive(flowOf(false), flowOf(DeviceOwnership.BACKGROUND)).first())
        assertTrue(backgroundActive(flowOf(true), flowOf(DeviceOwnership.IDLE)).first())
    }

    @Test
    fun `collect all waits for an interactive sync on any device`() {
        fun device(ownership: DeviceOwnership, running: Boolean = false) =
            DeviceSnapshot("a", null, ConnectionState.Disconnected, SyncProgress(running = running), null, false, null, ownership)
        assertFalse(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE))))
        assertTrue(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE), device(DeviceOwnership.INTERACTIVE))))
        assertTrue(anyCollectionActive(false, listOf(device(DeviceOwnership.IDLE, running = true))))
        assertTrue(anyCollectionActive(true, emptyList()))
    }
}
