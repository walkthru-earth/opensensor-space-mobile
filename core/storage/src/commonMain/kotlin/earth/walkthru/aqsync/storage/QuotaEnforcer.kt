package earth.walkthru.aqsync.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the `derived/v1` day files under a byte quota (#61). It runs after each derive pass and at
 * janitor time, and evicts day files least recently written first until the derived root is under
 * the quota. Manifests stay, so a derive pass can tell an evicted day from a stale one and does not
 * rebuild it. The day is rebuilt when something reads it.
 *
 * Only paths under a [StorageClass.Rebuildable] root are ever deleted. A path that classifies as
 * anything else is refused and counted. It blocks on disk I/O, so callers run it on their I/O
 * dispatcher. It logs counts only, never paths or station ids.
 */
class QuotaEnforcer(
    private val roots: StorageRoots,
    private val tree: StorageTree,
    private val log: (String) -> Unit = {},
) {
    data class Report(
        val bytesBefore: Long = 0,
        val bytesAfter: Long = 0,
        val evicted: Int = 0,
        /** Deletes refused because the path was not under a Rebuildable root. */
        val refused: Int = 0,
        val failures: Int = 0,
    ) {
        fun describe(): String = "derived quota: $bytesBefore to $bytesAfter bytes, evicted $evicted, refused $refused, failures $failures"
    }

    fun enforce(quotaBytes: Long): Report {
        val dir = roots[StorageRole.Derived] ?: return Report()
        val files = tree.list(dir).filter { !it.isDirectory }
        val before = files.sumOf { it.bytes }
        var report = Report(bytesBefore = before, bytesAfter = before)
        if (before <= quotaBytes) return report
        // Least recently written first. The path breaks ties so the order is stable.
        val candidates = files.filter { it.path.endsWith(DAY_SUFFIX) }.sortedWith(compareBy({ it.modifiedEpochMs }, { it.path }))
        var current = before
        for (entry in candidates) {
            if (current <= quotaBytes) break
            val path = "$dir/${entry.path}"
            if (roots.classOf(path) != StorageClass.Rebuildable) {
                report = report.copy(refused = report.refused + 1)
                continue
            }
            try {
                tree.delete(path)
                current -= entry.bytes
                report = report.copy(evicted = report.evicted + 1)
            } catch (e: Exception) {
                report = report.copy(failures = report.failures + 1)
            }
        }
        report = report.copy(bytesAfter = current)
        log(report.describe())
        return report
    }

    private companion object {
        const val DAY_SUFFIX = ".parquet"
    }
}

/**
 * The derived quota as each platform computes it, with its [enforcer]. The derive pass, the janitor
 * and the Settings storage card share one instance. [enforce] blocks on disk I/O like [QuotaEnforcer.enforce].
 * While a reader [holding] the day files runs, nothing is evicted, so an export never loses a file it
 * listed. When the last hold ends the quota is applied at once, whatever the reader's outcome.
 */
class DerivedQuota(
    private val enforcer: QuotaEnforcer,
    /** Where the trim at the end of a hold runs, since a hold may end on the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val quotaBytes: suspend () -> Long = { RetentionPolicy.DEFAULT_DERIVED_QUOTA_BYTES },
) {
    suspend fun bytes(): Long = quotaBytes()

    private val lock = Mutex()
    private var holders = 0

    /** A [clear] that arrived during a hold, applied when the hold ends. */
    private var clearDeferred = false

    suspend fun enforce(): QuotaEnforcer.Report = evict(clearing = false)

    /** Evicts every derived day file, the Settings clear action. Manifests stay, so a read rebuilds. */
    suspend fun clear(): QuotaEnforcer.Report = evict(clearing = true)

    /**
     * Runs [block] with eviction paused. It waits for an eviction already running. When the last
     * hold ends, normally, by an exception or by cancellation, the quota is enforced (or a [clear]
     * that came in meanwhile runs), so a pass that rebuilt nothing cannot leave the cache above
     * quota (#61). A failed trim then is not the reader's error, the next one retries it.
     */
    suspend fun <T> holding(block: suspend () -> T): T {
        lock.withLock { holders++ }
        try {
            return block()
        } finally {
            withContext(NonCancellable + io) {
                lock.withLock {
                    if (--holders == 0) {
                        val quota = if (clearDeferred) 0 else runCatching { quotaBytes() }.getOrNull()
                        clearDeferred = false
                        if (quota != null) runCatching { enforcer.enforce(quota) }
                    }
                }
            }
        }
    }

    private suspend fun evict(clearing: Boolean): QuotaEnforcer.Report = lock.withLock {
        when {
            holders == 0 -> enforcer.enforce(if (clearing) 0 else quotaBytes())
            clearing -> QuotaEnforcer.Report().also { clearDeferred = true }
            else -> QuotaEnforcer.Report()
        }
    }
}
