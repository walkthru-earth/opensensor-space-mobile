package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The order of the History passes over one phone archive, shared by Android and iOS. The platforms
 * keep their own derive (Room tiers on Android, day files and tier files on iOS) and hand it in, the
 * order lives here.
 *
 * - **Janitor first.** [run] sweeps storage before the first backfill, so an orphan file the janitor
 *   adopts is indexed in the same start.
 * - **Backfill, then derive.** A backfill pass and a derive pass never overlap. Every derive goes
 *   through [derivePass], which waits for a backfill in flight, and a backfill waits for a derive in
 *   flight. After each backfill [run] derives once, so History catches up with what it indexed.
 * - **Held days.** A derive that still runs while a record waits for its index keeps that record's
 *   days in History, the shared rule of [StationDays.kept].
 * - **Prune.** The old file cleanup writes its tombstone under the writer lease of the
 *   [earth.walkthru.aqsync.data.sync.DeviceCoordinator], then its `afterRemoval` derives the stations
 *   it emptied through [derivePass], ordered like any derive.
 *
 * [derivePass] is not reentrant. A platform derive takes it once, around its whole pass.
 */
class HistoryOrchestrator(
    private val repair: ArchiveIndexRepair,
    private val index: ArchiveIndex,
    /** The startup storage sweep and the quotas that follow it. Its failure is logged, the start goes on. */
    private val janitor: suspend () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val passes = Mutex()

    /** Runs [block] as the one History pass of this archive, after any backfill or derive in flight. */
    suspend fun <T> derivePass(block: suspend () -> T): T = passes.withLock { block() }

    /** Indexes every record waiting for its index, as one pass. */
    suspend fun backfill() = passes.withLock { repair.backfillUntilSettled() }

    /**
     * The life of History in this process. The janitor, a backfill, then [deriveAll], and again a
     * backfill then [deriveAll] whenever a record waits for its index, until cancelled. [deriveAll]
     * takes [derivePass] itself. Call once per process.
     */
    suspend fun run(deriveAll: suspend () -> Unit) {
        step("storage janitor") { janitor() }
        backfillThenDerive(deriveAll)
        try {
            index.observeNeverIndexed().conflate().collect { waiting -> if (waiting) backfillThenDerive(deriveAll) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The index flow failed. The next process start repairs again.
            log("index repair stopped: ${e::class.simpleName}")
        }
    }

    private suspend fun backfillThenDerive(deriveAll: suspend () -> Unit) {
        backfill()
        step("history derive") { deriveAll() }
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
