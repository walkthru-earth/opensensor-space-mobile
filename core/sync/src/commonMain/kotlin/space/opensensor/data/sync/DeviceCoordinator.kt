package space.opensensor.data.sync

import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.PrunedFiles
import space.opensensor.collection.CollectionLink
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.sync.SyncLock

/**
 * The one owner of who may change one phone archive, and in what order, shared by Android and iOS.
 * Make one per archive and hand it to every writer there: the [SyncEngine], the storage janitor,
 * the index repair and the old file cleanup. Two writers built with different coordinators would
 * not see each other, so no writer makes its own.
 *
 * - **Admission.** One collection per thing at a time ([tryAdmit], [admit]). A second link to the
 *   same thing, Bluetooth beside Wi-Fi, is refused as busy and waits for the next attempt.
 * - **Writer lease.** One writer per archive identity ([withWriter]), waiters included. A download,
 *   the janitor's partial sweep and adoption, and the old file cleanup of that file take it.
 * - **Thing gate.** One index or file mutation per thing ([withThing]), held only around the
 *   short change, so a row read under it is still live when it is written.
 * - **Lock order.** A writer lease first, then the thing gate. Never a
 *   lease while holding the gate, and never the gate twice, since neither lock is reentrant.
 * - **Tombstones.** A file the user removed in Settings is in [pruned]. The engine checks
 *   [removedByUser] under the writer lease before `OPEN`, and the cleanup writes the tombstone
 *   under the same lease, so a sync planned before the removal never downloads the file again.
 */
class DeviceCoordinator(
    /** Files removed in Settings, which sync skips while the device lists them at the same size. */
    val pruned: PrunedFiles = PrunedFiles.None,
) {
    private val leases = WriterLeases()
    private val gate = ArchiveMutationGate()
    private val lock = SyncLock()
    private val admitted = HashSet<String>()

    /** Claims [thing] for one collection. False while another collection of it runs on this phone. */
    fun tryAdmit(thing: String): Boolean = lock.withLock { admitted.add(thing) }

    /** Frees [thing] for the next collection. Safe to call for a thing not admitted. */
    fun release(thing: String) {
        lock.withLock { admitted.remove(thing) }
    }

    /**
     * [link] admitted for its thing. The returned link's release runs [beforeRelease], hands [link]
     * back, then frees the thing, so a collection it admits next finds the link idle. When another
     * collection holds the thing, [link] is released at once and this throws a busy
     * [LinkClosedException], which the controller retries like any busy link.
     */
    fun admit(link: CollectionLink, beforeRelease: (CollectionLink) -> Unit = {}): CollectionLink {
        val thing = link.thing
        if (!tryAdmit(thing)) {
            link.release()
            throw LinkClosedException(DisconnectCause.Busy)
        }
        lateinit var outer: CollectionLink
        outer = CollectionLink(link.session, thing, link.maxRead) {
            try {
                beforeRelease(outer)
                link.release()
            } finally {
                release(thing)
            }
        }
        return outer
    }

    /** Runs [block] holding the writer lease of [key]. */
    suspend fun <T> withWriter(key: ArchiveKey, block: suspend () -> T): T = leases.withLease(key, block)

    /** Runs [block] holding the gate of [thing]. Never call it with the gate already held. */
    suspend fun <T> withThing(thing: String, block: suspend () -> T): T = gate.withThing(thing, block)

    /**
     * Whether the user removed [key] at [bytes] from this phone, the planner's REMOVED rule. Call it
     * under the writer lease of [key], so the answer holds until the lease ends.
     */
    suspend fun removedByUser(key: ArchiveKey, bytes: Long): Boolean =
        pruned.forThing(key.thing).any { it.name == key.name && it.bytes == bytes }

    /** Things admitted now, for tests. */
    internal fun admittedCount(): Int = lock.withLock { admitted.size }

    /** Writer leases held or waited for, for tests. */
    internal fun activeWriters(): Int = leases.activeCount()

    /** Thing gates held or waited for, for tests. */
    internal fun activeThings(): Int = gate.activeCount()
}
