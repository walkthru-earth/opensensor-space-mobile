package space.opensensor.archive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who may change one phone archive, and in what order. The sync side (`DeviceCoordinator` in
 * `:core:sync`) adds collection admission on top. History, index repair and the old file cleanup see
 * only this port, so they need nothing from the link stack.
 *
 * - **Writer lease.** One writer per archive identity ([withWriter]), waiters included.
 * - **Thing gate.** One index or file mutation per thing ([withThing]), held only around the short
 *   change, so a row read under it is still live when it is written.
 * - **Lock order.** A writer lease first, then the thing gate. Never a lease while holding the gate,
 *   and never the gate twice, since neither lock is reentrant.
 * - **Tombstones.** [pruned] holds the files the user removed in Settings.
 *
 * Every writer of one archive must share one instance, or they would not see each other.
 */
interface ArchiveWriters {
    /** Files removed in Settings, which sync skips while the device lists them at the same size. */
    val pruned: PrunedFiles

    /** Runs [block] holding the writer lease of [key]. */
    suspend fun <T> withWriter(key: ArchiveKey, block: suspend () -> T): T

    /** Runs [block] holding the gate of [thing]. Never call it with the gate already held. */
    suspend fun <T> withThing(thing: String, block: suspend () -> T): T
}

/** The leases and gates of one archive, the [ArchiveWriters] every writer there shares. */
class ArchiveGates(override val pruned: PrunedFiles = PrunedFiles.None) : ArchiveWriters {
    private val leases = KeyedMutex<ArchiveKey>()
    private val gate = KeyedMutex<String>()

    override suspend fun <T> withWriter(key: ArchiveKey, block: suspend () -> T): T = leases.with(key, block)

    override suspend fun <T> withThing(thing: String, block: suspend () -> T): T = gate.with(thing, block)

    /** Writer leases held or waited for, for tests. */
    fun activeWriters(): Int = leases.activeCount()

    /** Thing gates held or waited for, for tests. */
    fun activeThings(): Int = gate.activeCount()
}

/**
 * One holder per key at a time, waiters included. A slot lives only while someone holds or waits for
 * it, so a cancelled waiter never leaves one behind.
 */
internal class KeyedMutex<K : Any> {
    private class Slot(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val lock = ArchiveLock()
    private val slots = HashMap<K, Slot>()

    suspend fun <T> with(key: K, block: suspend () -> T): T {
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

    /** Keys held or waited for, for tests. */
    fun activeCount(): Int = lock.withLock { slots.size }
}

/**
 * A reentrant lock for short, non-suspending critical sections. JVM uses a monitor and iOS an
 * `NSRecursiveLock`. Suspending code uses a coroutine `Mutex` instead.
 */
internal expect class ArchiveLock() {
    fun <T> withLock(block: () -> T): T
}
