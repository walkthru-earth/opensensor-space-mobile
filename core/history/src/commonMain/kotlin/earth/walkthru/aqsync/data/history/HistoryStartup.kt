package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.storage.StorageJanitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The History start of one phone process, the same on Android and iOS (#125). Each platform builds
 * one over its archive and calls [start] once at process start, so neither can drop a step.
 *
 * 1. The storage [janitor] sweeps temps and stale partials and adopts orphan finals (#60). It logs its
 *    own report.
 * 2. The feed [blocks] quota applies, even when the janitor failed (#94).
 * 3. [HistoryOrchestrator.run], the backfill and the platform derive, so a file the janitor adopted is
 *    indexed in the same start.
 *
 * [log] gets step names, counts and class names only, never paths or station ids.
 */
@OptIn(ExperimentalAtomicApi::class)
class HistoryStartup(
    val orchestrator: HistoryOrchestrator,
    private val janitor: StorageJanitor,
    private val blocks: BlockCache,
    private val log: (String) -> Unit = {},
) {
    private val job = AtomicReference<Job?>(null)

    /** True once [start] launched the History life of this process. */
    val started: Boolean get() = job.load() != null

    /**
     * Launches the steps above in [scope] and returns their job. [deriveAll] is the platform's derive
     * entry point, which takes [HistoryOrchestrator.derivePass] itself. A second call returns the
     * first job and starts nothing.
     */
    fun start(scope: CoroutineScope, deriveAll: suspend () -> Unit): Job {
        job.load()?.let { return it }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                step("storage janitor") { janitor.run() }
            } finally {
                step("feed block quota") { blocks.enforce() }
            }
            orchestrator.run(deriveAll)
        }
        if (!job.compareAndSet(null, launched)) {
            launched.cancel()
            return job.load()!!
        }
        launched.start()
        return launched
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$name failed: ${e::class.simpleName}")
        }
    }
}
