package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import space.opensensor.archive.ArchiveIndex
import space.opensensor.model.BoardTemplate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The one gate between served templates and History work, the same on Android and iOS. A template
 * matters to History only through the template each indexed file resolves to, which every day
 * signature names. [run] turns template changes into the things whose files resolve differently,
 * [HistoryStartup] derives those things and [ArchiveHistoryTiers] rebuilds them through [generation].
 *
 * 1. Changes settle for [settle] and coalesce, so a burst of served templates costs one check.
 * 2. When no indexed file could resolve differently, one indexed query, the check ends there. A file
 *    could when it names the desc of a template added or removed, or names its dict and does not
 *    resolve by its own desc both before and after.
 * 3. Otherwise each indexed file resolves over the templates before and after, and only the things with
 *    a file whose resolved digest changed derive again.
 * 4. After a check that read the index, whether it derived or not, the next check waits for [cooldown].
 *    A board that keeps a known dict and rotates its desc therefore costs a bounded number of reads and
 *    passes. Sync-driven derives never wait.
 * 5. A check whose index read or derive failed keeps the change pending and tries again after the
 *    cooldown, so a failing index retries at the cooldown's pace and never in a tight loop.
 *
 * [log] gets class names only, never paths or thing ids.
 */
@OptIn(FlowPreview::class)
class TemplateRederive(
    private val index: ArchiveIndex,
    private val templates: FileTemplates = FileTemplates.SERVED,
    private val settle: Duration = SETTLE,
    private val cooldown: Cooldown = Cooldown(),
    private val log: (String) -> Unit = {},
) {
    /** How many template checks re-derived each thing, so a tier upstream rebuilds only its own thing. */
    private val generations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * The templates held when this gate is made, before any reader it serves builds a day. A template
     * that lands before [run] starts, as the cache loads during the janitor, still counts as a change.
     */
    private val baseline = templates.changes.value

    /** Counts up each time a template change changes what one of [thing]'s indexed files resolves to. */
    fun generation(thing: String): Flow<Long> = generations.map { it[thing] ?: 0L }.distinctUntilChanged()

    /**
     * Watches the templates until cancelled and calls [derive] with the things a change touched, from
     * the [baseline]. [derive] returns false when it failed, and the change then stays pending. Called
     * once per gate.
     */
    suspend fun run(derive: suspend (Set<String>) -> Boolean) {
        var seen = baseline
        templates.changes.debounce(settle).conflate().collect {
            var pending = true
            while (pending) {
                delay(cooldown.remaining())
                val now = templates.changes.value
                val things = affected(seen, now) ?: continue
                if (things.isNotEmpty()) {
                    generations.update { held -> held + things.associateWith { (held[it] ?: 0L) + 1 } }
                    if (!derive(things)) continue
                }
                seen = now
                pending = false
            }
        }
    }

    /**
     * The things with an indexed file that resolves to another template under [after] than under [before],
     * null when the index read failed.
     */
    private suspend fun affected(before: List<BoardTemplate>, after: List<BoardTemplate>): Set<String>? {
        val changed = changed(before, after)
        if (changed.isEmpty() || !named(changed, templates.pinned(before) intersect templates.pinned(after))) return emptySet()
        // The read below is the costly step, so it waits for the cooldown even when it finds nothing.
        cooldown.fired()
        val was = templates.at(before)
        val now = templates.at(after)
        return try {
            index.observeAll().first().filter { it.isIndexed && was.digestOf(it) != now.digestOf(it) }.mapTo(HashSet()) { it.thing }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cooldown already fired, so the retry waits for it.
            log("template scope failed: ${e::class.simpleName}")
            null
        }
    }

    /**
     * True when an indexed file could resolve differently after [changed], with [pinned] the desc
     * matches that hold before and after, and when the index cannot say.
     */
    private suspend fun named(changed: Set<BoardTemplate>, pinned: Set<String>): Boolean = try {
        index.anyIndexedNames(changed.mapNotNullTo(HashSet()) { it.key.desc }, changed.mapTo(HashSet()) { it.dict }, pinned)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("template match failed: ${e::class.simpleName}")
        true
    }

    /**
     * How long template checks wait after one that read the index. The first waits [first], each next one twice
     * the last up to [longest], and after [quiet] with no read it starts again at [first]. One per
     * process, read only by [run]'s single collector.
     */
    class Cooldown(
        private val first: Duration = 30.seconds,
        private val longest: Duration = 10.minutes,
        private val quiet: Duration = 10.minutes,
        private val clock: TimeSource = TimeSource.Monotonic,
    ) {
        private var wait = Duration.ZERO
        private var last: TimeMark? = null

        /** How long the next check still waits, zero when it may run now. */
        fun remaining(): Duration = last?.let { (wait - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

        /** Records a check that read the index. */
        fun fired() {
            val since = last?.elapsedNow()
            wait = if (since == null || since >= wait + quiet) first else (wait * 2).coerceAtMost(longest)
            last = clock.markNow()
        }
    }

    companion object {
        /** How long template changes settle before one check, so a burst of served templates costs one. */
        val SETTLE: Duration = 2.seconds

        /** The templates held in only one of [before] and [after], the ones added or removed. */
        private fun changed(before: List<BoardTemplate>, after: List<BoardTemplate>): Set<BoardTemplate> {
            val was = before.associateBy { it.key }
            val now = after.associateBy { it.key }
            return (was - now.keys).values.toSet() + (now - was.keys).values
        }
    }
}
