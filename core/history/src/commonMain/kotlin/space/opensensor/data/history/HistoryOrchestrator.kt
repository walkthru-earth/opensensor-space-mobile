package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.opensensor.archive.ArchiveIndex
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The order of the History passes over one phone archive, shared by Android and iOS. The platforms
 * keep their own derive (Room tiers on Android, day files and tier files on iOS) and hand it in, the
 * order lives here. [HistoryStartup] runs the janitor and the feed block quota before [run].
 *
 * - **Backfill, then derive.** A backfill pass and a derive pass never overlap. Every derive goes
 *   through [derivePass], which waits for a backfill in flight, and a backfill waits for a derive in
 *   flight. After each backfill [run] derives once, so History catches up with what it indexed.
 * - **Time estimates.** [estimateTimes] rewrites the estimated spans a derive reads, so it is a pass
 *   too. A derive that wants fresh estimates calls [ArchiveIndexRepair.estimateTimes] inside its pass,
 *   and a clock receipt follow-up runs [afterClockReceipt].
 * - **Retry.** A backfill that leaves a record waiting for its index, because the index threw, runs
 *   again after each of [retryDelays], then waits for the next index change.
 * - **Held days.** A derive that still runs while a record waits for its index keeps that record's
 *   days in History, the shared rule of [StationDays.kept].
 * - **Prune.** The old file cleanup writes its tombstone under the writer lease of the
 *   [space.opensensor.data.sync.DeviceCoordinator], then its `afterRemoval` derives the stations
 *   it emptied through [derivePass], ordered like any derive.
 *
 * [derivePass] is not reentrant. A platform derive takes it once, around its whole pass.
 */
class HistoryOrchestrator(
    private val repair: ArchiveIndexRepair,
    private val index: ArchiveIndex,
    private val log: (String) -> Unit = {},
    /** The waits before each retry of a backfill that left records waiting. */
    private val retryDelays: List<Duration> = RETRY_DELAYS,
) {
    private val passes = Mutex()

    /** Runs [block] as the one History pass of this archive, after any backfill or derive in flight. */
    suspend fun <T> derivePass(block: suspend () -> T): T = passes.withLock { block() }

    /**
     * [derive] behind [derivePass], as a platform's one derive entry point. Hand the platform derive
     * body only to this, so no caller can run it outside a pass.
     */
    fun <A> derivePassOf(derive: suspend (A) -> Unit): suspend (A) -> Unit = { arg -> derivePass { derive(arg) } }

    /** Indexes every record waiting for its index, as one pass. */
    suspend fun backfill() = passes.withLock { repair.backfillUntilSettled() }

    /**
     * [ArchiveIndexRepair.estimateTimes] as one pass. It rewrites the estimate columns that a derive
     * reads for its source sets and signatures, so it never runs beside a derive or a backfill.
     */
    suspend fun estimateTimes(): Int = passes.withLock { repair.estimateTimes() }

    /**
     * The follow-up to a saved clock receipt. The time estimates as one pass, so they never change
     * under a derive or a backfill, then [derive], which takes [derivePass] itself.
     */
    suspend fun afterClockReceipt(derive: suspend () -> Unit) {
        estimateTimes()
        derive()
    }

    /**
     * The life of History in this process. A backfill, then [deriveAll], and again a backfill then
     * [deriveAll] whenever a record waits for its index, until cancelled. [deriveAll] takes
     * [derivePass] itself. [HistoryStartup.start] calls it once per process.
     */
    suspend fun run(deriveAll: suspend () -> Unit) {
        // The first emission is the start pass, so a record still waiting after the retries is not
        // retried again until the index changes.
        var started = false
        try {
            index.observeNeverIndexed().conflate().collect { waiting ->
                if (waiting || !started) {
                    started = true
                    settle(deriveAll)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The index flow failed. The next process start repairs again.
            log("index repair stopped: ${e::class.simpleName}")
        }
        if (!started) settle(deriveAll)
    }

    /**
     * A backfill and a derive, again after each of [retryDelays] while a record still waits. The index
     * flow emits only when waiting changes, so without this a failed backfill would wait for the next
     * process start.
     */
    private suspend fun settle(deriveAll: suspend () -> Unit) {
        backfillThenDerive(deriveAll)
        for (wait in retryDelays) {
            if (!stillWaiting()) return
            log("index repair retry in ${wait.inWholeSeconds} s")
            delay(wait)
            backfillThenDerive(deriveAll)
        }
    }

    /** True while a never indexed record is left, and when the index cannot say. */
    private suspend fun stillWaiting(): Boolean = try {
        index.unindexed(1).any { it.awaitsIndex }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    private suspend fun backfillThenDerive(deriveAll: suspend () -> Unit) {
        step("index repair") { backfill() }
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

    companion object {
        /** 30 s doubling to 8 min, about a quarter of an hour of retries in all. */
        val RETRY_DELAYS: List<Duration> = listOf(30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes)
    }
}
