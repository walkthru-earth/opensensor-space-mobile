package earth.walkthru.aqsync.archive

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The lifetime of export runs, shared by every [ExportSpace] and the startup janitor (#161). Runs
 * coexist. A run is removed when its owner deletes it after a close signal, or once it is released
 * and [MAX_AGE_MS] old. A run that is still staging or shown holds a lease and is never swept by age.
 * Starting a new export is never taken as proof that an older recipient finished reading.
 */
@OptIn(ExperimentalAtomicApi::class)
object ExportRuns {
    /** Every run directory is `run-<uuid>`, directly under the export root. */
    const val PREFIX: String = "run-"

    /**
     * A released run this old has left the share sheet. Android's chooser gives no close signal, so
     * this bounds how long a share stays staged after it was handed over.
     */
    const val MAX_AGE_MS: Long = 60L * 60_000L

    private val held = AtomicReference(emptySet<String>())

    /** Marks run [name] in use, so no age sweep removes it until [release]. */
    fun lease(name: String) = update { it + name }

    /** Ends the lease of run [name]. Safe to call twice. */
    fun release(name: String) = update { it - name }

    fun isLeased(name: String): Boolean = name in held.load()

    /**
     * Whether run [name] may be swept at [now]. The lease is checked before [modified] is read, and a
     * release refreshes the run's time before it ends the lease, so a run released during the check
     * is seen with its fresh time and kept.
     */
    fun isExpired(name: String, now: Long, modified: () -> Long?): Boolean {
        if ('/' in name || !name.startsWith(PREFIX) || isLeased(name)) return false
        val at = modified() ?: return false
        return now - at >= MAX_AGE_MS
    }

    private inline fun update(change: (Set<String>) -> Set<String>) {
        while (true) {
            val old = held.load()
            if (held.compareAndSet(old, change(old))) return
        }
    }
}
