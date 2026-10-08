package earth.walkthru.aqsync.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncWorkerNotificationIdTest {
    @Test
    fun `notification id is stable per work request and never hits a fixed id`() {
        val fixed = setOf(SyncNotifications.FOREGROUND_ID, SyncNotifications.WORKER_ID, SyncNotifications.SUMMARY_ID)
        val random = java.util.Random(42)
        repeat(10_000) {
            val id = UUID(random.nextLong(), random.nextLong())
            val n = SyncWorker.notificationIdFor(id)
            assertEquals("same request, same id after a process restart", n, SyncWorker.notificationIdFor(UUID(id.mostSignificantBits, id.leastSignificantBits)))
            assertTrue(n >= SyncWorker.NOTIFICATION_ID_BASE)
            assertTrue(n !in fixed)
        }
        val extreme = UUID(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue(SyncWorker.notificationIdFor(extreme) > 0)
    }
}
