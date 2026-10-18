package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.sync.SyncLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One writer per archive identity (station, name), waiters included. Different files transfer
 * together. A slot lives only while someone holds or waits for it, so a cancelled waiter never leaves
 * a lease behind. Only [DeviceCoordinator] makes one, so every writer of an archive shares it.
 */
internal class WriterLeases {
    private class Slot(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val lock = SyncLock()
    private val slots = HashMap<ArchiveKey, Slot>()

    suspend fun <T> withLease(key: ArchiveKey, block: suspend () -> T): T {
        val slot = lock.withLock { slots.getOrPut(key) { Slot() }.also { it.users++ } }
        try {
            return slot.mutex.withLock { block() }
        } finally {
            lock.withLock {
                slot.users--
                if (slot.users == 0) slots.remove(key)
            }
        }
    }

    /** Identities held or waited for, for tests. */
    internal fun activeCount(): Int = lock.withLock { slots.size }
}
