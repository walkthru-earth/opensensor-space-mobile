package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import space.opensensor.model.BoardTemplate
import space.opensensor.model.TemplateKey
import space.opensensor.net.BlockCache
import space.opensensor.storage.StorageJanitor
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The History start of one phone process, the same on Android and iOS (#125). Each platform builds
 * one over its archive and calls [start] once at process start, so neither can drop a step.
 *
 * 1. The storage [janitor] sweeps temps and stale partials and adopts orphan finals (#60). It logs its
 *    own report.
 * 2. The feed [blocks] quota applies, even when the janitor failed (#94).
 * 3. [HistoryOrchestrator.run], the backfill and the platform derive, so a file the janitor adopted is
 *    indexed in the same start.
 * 4. Beside it, the platform derive again when [templates] change, so a template the cache loads or a
 *    board serves after a day was derived rebuilds the days of its files (their signatures name it).
 *    Changes settle for [settle] and coalesce into one check. It derives only when a template added or
 *    removed names a desc or dict that an indexed file's footer names, one indexed query. A board that
 *    serves fresh templates no file uses therefore costs no derive.
 *
 * [log] gets step names, counts and class names only, never paths or thing ids.
 */
@OptIn(ExperimentalAtomicApi::class, FlowPreview::class)
class HistoryStartup(
    val orchestrator: HistoryOrchestrator,
    private val janitor: StorageJanitor,
    private val blocks: BlockCache,
    private val log: (String) -> Unit = {},
    private val templates: StateFlow<List<BoardTemplate>> = FileTemplates.SERVED.changes,
    private val settle: Duration = TEMPLATE_SETTLE,
) {
    private val job = AtomicReference<Job?>(null)

    /** True once [start] launched the History life of this process. */
    val started: Boolean get() = job.load() != null

    /**
     * Launches the steps above in [scope] and returns their job, which lives as long as [scope] to watch
     * [templates]. [deriveAll] is the platform's derive entry point, which takes
     * [HistoryOrchestrator.derivePass] itself. A second call returns the first job and starts nothing.
     */
    fun start(scope: CoroutineScope, deriveAll: suspend () -> Unit): Job {
        job.load()?.let { return it }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                step("storage janitor") { janitor.run() }
            } finally {
                step("feed block quota") { blocks.enforce() }
            }
            // Read before the first derive, so a template that lands while it runs derives once more.
            var seen = templates.value
            coroutineScope {
                launch { orchestrator.run(deriveAll) }
                templates.debounce(settle).conflate().collect { now ->
                    val changed = changed(seen, now)
                    seen = now
                    if (changed.isNotEmpty()) step("template derive") { if (named(changed)) deriveAll() }
                }
            }
        }
        if (!job.compareAndSet(null, launched)) {
            launched.cancel()
            return job.load()!!
        }
        launched.start()
        return launched
    }

    /** True when an indexed file names a desc or dict of [changed], and when the index cannot say. */
    private suspend fun named(changed: Set<TemplateKey>): Boolean = try {
        orchestrator.index.anyIndexedNames(changed.mapNotNullTo(HashSet()) { it.desc }, changed.mapTo(HashSet()) { it.dict })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("template match failed: ${e::class.simpleName}")
        true
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

    companion object {
        /** How long template changes settle before one check, so a burst of served templates costs one. */
        val TEMPLATE_SETTLE: Duration = 2.seconds

        /** The keys held in only one of [before] and [after], the templates added or removed. */
        private fun changed(before: List<BoardTemplate>, after: List<BoardTemplate>): Set<TemplateKey> {
            val was = before.mapTo(HashSet()) { it.key }
            val now = after.mapTo(HashSet()) { it.key }
            return (was - now) + (now - was)
        }
    }
}
