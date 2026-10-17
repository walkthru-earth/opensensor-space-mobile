package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.sync.SyncLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One archive mutation per station at a time. Sync adds rows, the storage janitor adopts files,
 * `ArchiveIndexRepair` updates rows, and `LegacyFileCleanup` removes rows and files. Each takes the
 * gate only around its short index and file change, so a row it reads is still live when it writes.
 * Readers never take it.
 *
 * Lock order is a [WriterLeases] lease first, then the gate. Never take a lease while holding the
 * gate, and never nest the gate, since the mutex is not reentrant. One instance per archive, shared
 * by every writer there.
 */
class ArchiveMutationGate {
    private class Slot(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val lock = SyncLock()
    private val slots = HashMap<String, Slot>()

    suspend fun <T> withStation(station: String, block: suspend () -> T): T {
        val slot = lock.withLock { slots.getOrPut(station) { Slot() }.also { it.users++ } }
        try {
            return slot.mutex.withLock { block() }
        } finally {
            lock.withLock {
                slot.users--
                if (slot.users == 0) slots.remove(station)
            }
        }
    }

    /** Stations held or waited for, for tests. */
    internal fun activeCount(): Int = lock.withLock { slots.size }
}
