package earth.walkthru.aqsync.data.background

import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AutoSyncSchedulerTest {
    private class Entry(val name: String, val tags: Set<String>, val periodic: Boolean, var running: Boolean = false, var finished: Boolean = false)

    /** WorkManager's `KEEP` without WorkManager: a second request under a live name is dropped. */
    private class FakeQueue : SyncWorkQueue {
        val entries = MutableStateFlow<List<Entry>>(emptyList())
        val requests = mutableListOf<Pair<String, OneTimeWorkRequest>>()

        override suspend fun pending(uniqueName: String) = entries.value.any { it.name == uniqueName && !it.finished }

        override fun enqueue(uniqueName: String, request: OneTimeWorkRequest) {
            requests += uniqueName to request
            if (entries.value.none { it.name == uniqueName && !it.finished }) entries.value = entries.value + Entry(uniqueName, request.tags, periodic = false)
        }

        override fun enqueuePeriodic(uniqueName: String, request: PeriodicWorkRequest) {
            if (entries.value.none { it.name == uniqueName && !it.finished }) entries.value = entries.value + Entry(uniqueName, request.tags, periodic = true)
        }

        override fun cancel(uniqueName: String) {
            entries.value = entries.value.filterNot { it.name == uniqueName }
        }

        override fun observe(tag: String): Flow<List<QueuedWork>> = entries.map { all ->
            all.filter { tag in it.tags && !it.finished }.map { QueuedWork(running = it.running, enqueued = !it.running, periodic = it.periodic) }
        }

        fun update(name: String, change: Entry.() -> Unit) {
            entries.value.filter { it.name == name }.forEach(change)
            entries.value = entries.value.toList()
        }
    }

    private val unusedDao = Proxy.newProxyInstance(KnownDeviceDao::class.java.classLoader, arrayOf(KnownDeviceDao::class.java)) { _, _, _ -> error("not used") } as KnownDeviceDao
    private val queue = FakeQueue()
    private val scheduler = AutoSyncScheduler(unusedDao, queue)

    @Test
    fun `a second sync now for the same device says already queued and adds nothing`() = runTest {
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.syncNow(A))
        assertEquals(AutoSyncScheduler.Enqueue.ALREADY_QUEUED, scheduler.syncNow(A))
        assertEquals(1, queue.requests.size)

        queue.update(AutoSyncScheduler.deviceWorkName(A, SyncTrigger.MANUAL)) { running = true }
        assertEquals(AutoSyncScheduler.Enqueue.ALREADY_QUEUED, scheduler.syncNow(A))

        queue.update(AutoSyncScheduler.deviceWorkName(A, SyncTrigger.MANUAL)) { finished = true }
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.syncNow(A))
    }

    @Test
    fun `every one-time request names its device and the active flag stays per device`() = runTest {
        scheduler.syncNow(A)
        scheduler.syncNow(B, SyncTrigger.PRESENCE)
        val (nameA, requestA) = queue.requests[0]
        val (nameB, requestB) = queue.requests[1]
        assertEquals("auto-sync-now-$A", nameA)
        assertEquals("auto-sync-trigger-$B", nameB)
        assertTrue(AutoSyncScheduler.deviceTag(A) in requestA.tags)
        assertTrue(AutoSyncScheduler.deviceTag(B) in requestB.tags)
        assertFalse(AutoSyncScheduler.deviceTag(B) in requestA.tags)

        queue.update(nameA) { finished = true }
        assertFalse(scheduler.observeActive(A).first())
        assertTrue(scheduler.observeActive(B).first())
        assertTrue(scheduler.observeAnyActive().first())
    }

    @Test
    fun `a manual run and a trigger for one device queue under their own names`() = runTest {
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.syncNow(A))
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.syncNow(A, SyncTrigger.ADVERTISEMENT))
        assertEquals(AutoSyncScheduler.Enqueue.ALREADY_QUEUED, scheduler.syncNow(A, SyncTrigger.PRESENCE))
    }

    @Test
    fun `collect all has its own name and reports a pending run`() = runTest {
        scheduler.syncNow(A)
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.collectAll())
        assertEquals(AutoSyncScheduler.Enqueue.ALREADY_QUEUED, scheduler.collectAll())
        assertEquals(AutoSyncScheduler.COLLECT_ALL_NAME, queue.requests.last().first)
        assertFalse(AutoSyncScheduler.deviceTag(A) in queue.requests.last().second.tags)
        assertFalse(scheduler.observeActive(B).first())
    }

    @Test
    fun `the Wi-Fi trigger queues one app-wide run`() = runTest {
        assertEquals(AutoSyncScheduler.Enqueue.QUEUED, scheduler.syncOnTrigger(SyncTrigger.WIFI, lanOnly = true))
        assertEquals(AutoSyncScheduler.Enqueue.ALREADY_QUEUED, scheduler.syncOnTrigger(SyncTrigger.WIFI, lanOnly = true))
        assertEquals(AutoSyncScheduler.TRIGGER_ALL_NAME, queue.requests.single().first)
    }

    @Test
    fun `a periodic registration waiting for its next period is not active`() = runTest {
        scheduler.setPeriodicEnabled(true)
        assertFalse(scheduler.observeAnyActive().first())
        queue.update(AutoSyncScheduler.PERIODIC_NAME) { running = true }
        assertTrue(scheduler.observeAnyActive().first())
        scheduler.setPeriodicEnabled(false)
        assertFalse(scheduler.observeAnyActive().first())
    }

    private companion object {
        const val A = "AA:BB:CC:DD:EE:01"
        const val B = "AA:BB:CC:DD:EE:02"
    }
}
