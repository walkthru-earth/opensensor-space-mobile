package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import space.opensensor.net.BlockCache
import space.opensensor.storage.StorageJanitor
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
 * 4. Beside it [rederive], the one gate for template changes. It derives again only the things whose
 *    files resolve to another template, settled and with a cooldown, see [TemplateRederive]. A failed
 *    template derive stays pending and tries again after the cooldown.
 *
 * [log] gets step names, counts and class names only, never paths or thing ids.
 */
@OptIn(ExperimentalAtomicApi::class)
class HistoryStartup(
    val orchestrator: HistoryOrchestrator,
    private val janitor: StorageJanitor,
    private val blocks: BlockCache,
    private val log: (String) -> Unit = {},
    val rederive: TemplateRederive = TemplateRederive(orchestrator.index, log = log),
) {
    private val job = AtomicReference<Job?>(null)

    /** True once [start] launched the History life of this process. */
    val started: Boolean get() = job.load() != null

    /**
     * Launches the steps above in [scope] and returns their job, which lives as long as [scope] to run
     * [rederive]. [deriveAll] is the platform's derive entry point and [deriveThings] its derive of some
     * things, [deriveAll] when null. Each takes [HistoryOrchestrator.derivePass] itself. A second call
     * returns the first job and starts nothing.
     */
    fun start(scope: CoroutineScope, deriveThings: (suspend (Set<String>) -> Unit)? = null, deriveAll: suspend () -> Unit): Job {
        job.load()?.let { return it }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                step("storage janitor") { janitor.run() }
            } finally {
                step("feed block quota") { blocks.enforce() }
            }
            coroutineScope {
                launch { orchestrator.run(deriveAll) }
                // Compares with the templates held when the gate was made, so one that landed during the janitor derives once more.
                rederive.run { things -> step("template derive") { deriveThings?.invoke(things) ?: deriveAll() } }
            }
        }
        if (!job.compareAndSet(null, launched)) {
            launched.cancel()
            return job.load()!!
        }
        launched.start()
        return launched
    }

    /** Runs [block] and returns false when it failed, after logging the failure. */
    private suspend fun step(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$name failed: ${e::class.simpleName}")
        false
    }
}
