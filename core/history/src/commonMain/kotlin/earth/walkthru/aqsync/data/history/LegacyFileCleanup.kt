package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** UTC epoch days `[fromEpochDay, toEpochDay]`, both ends included. */
data class UtcDayRange(val fromEpochDay: Long, val toEpochDay: Long) {
    init {
        require(fromEpochDay <= toEpochDay) { "range ends before it starts" }
    }

    /** Start of the first day, inclusive, in nanoseconds. */
    val fromNs: Long get() = fromEpochDay * StationDays.DAY_NS

    /** Start of the day after the last one, exclusive, in nanoseconds. */
    val untilNs: Long get() = (toEpochDay + 1) * StationDays.DAY_NS

    /**
     * True when [record] has a real or estimated span overlapping the range. A file the timeline cannot
     * place (no UTC rows and no estimate, or never indexed) is outside every range.
     */
    fun covers(record: ArchiveRecord): Boolean {
        val first = record.timelineFirstNs ?: return false
        val last = record.timelineLastNs ?: return false
        return last >= fromNs && first < untilNs
    }
}

/**
 * Removes device files in the old layout ([UtcColumns.isLegacyLayout]) from the phone archive, from
 * Settings › Storage. Phone only. Nothing is sent to a device and its card is never touched, so a later
 * sync downloads a removed file again when the device still lists it.
 *
 * [plan] is the dry run, it reads each footer and changes nothing. [remove] works through a plan one
 * file at a time under its [WriterLeases] lease. It checks the file is still old, removes the index
 * record, then deletes the final file. A crash between the two leaves a final file without a record,
 * which the startup janitor adopts again, so the archive never holds a record for a missing file. A
 * delete that fails puts the record back. [remove] refuses while [syncRunning], and stops between
 * files when a sync starts.
 *
 * Cancellation is honoured between files only. Each file's steps, from the record removal to the
 * re-adopt check, finish under [NonCancellable], and [afterRemoval] runs for the files already removed
 * even when the caller is cancelled.
 *
 * Logs carry counts and exception class names only, never paths or station ids.
 */
class LegacyFileCleanup(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val roots: StorageRoots,
    private val tree: StorageTree,
    private val leases: WriterLeases,
    private val io: CoroutineDispatcher,
    /** True while any device sync or collection runs on this phone. */
    private val syncRunning: () -> Boolean,
    /** Rebuilds the derived days of the removed files, per station. */
    private val afterRemoval: suspend (Map<String, Set<Long>>) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    /** Old layout files the dry run found. [skipped] counts files whose footer could not be read, which are kept. */
    data class Plan(val keys: List<ArchiveKey>, val bytes: Long, val stations: Int, val skipped: Int) {
        val files: Int get() = keys.size
    }

    data class Report(
        val removed: Int = 0,
        val bytes: Long = 0,
        /** Files left in place, no longer old, gone already, unreadable, or not reached because a sync started. */
        val kept: Int = 0,
        /** Files whose record removal or delete threw. The file stays, with its record put back when that worked. */
        val failed: Int = 0,
        /** True when a sync started before the plan was done, or was running already. */
        val stoppedBySync: Boolean = false,
        /** True when [afterRemoval] threw. The files are gone, History catches up on its next rebuild. */
        val rebuildFailed: Boolean = false,
    )

    private sealed interface Outcome {
        class Removed(val record: ArchiveRecord) : Outcome

        data object Kept : Outcome

        data object Failed : Outcome
    }

    /** Every old layout file in [range], or in the whole archive when [range] is null. */
    suspend fun plan(range: UtcDayRange? = null): Plan {
        val keys = ArrayList<ArchiveKey>()
        var bytes = 0L
        var skipped = 0
        val stations = HashSet<String>()
        val unreadable = UnreadableLog()
        for (record in index.observeAll().first()) {
            if (range != null && !range.covers(record)) continue
            when (isLegacy(record.key, unreadable)) {
                true -> {
                    keys += record.key
                    bytes += record.bytes
                    stations += record.station
                }
                false -> Unit
                null -> skipped++
            }
        }
        log("old file cleanup: found ${keys.size} files in ${stations.size} stations, skipped $skipped")
        return Plan(keys, bytes, stations.size, skipped)
    }

    /** Removes the files of [plan] that are still old. */
    suspend fun remove(plan: Plan): Report {
        if (syncRunning()) return Report(kept = plan.files, stoppedBySync = true)
        var report = Report()
        val removed = ArrayList<ArchiveRecord>()
        val unreadable = UnreadableLog()
        try {
            for ((i, key) in plan.keys.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (syncRunning()) {
                    report = report.copy(kept = report.kept + plan.files - i, stoppedBySync = true)
                    break
                }
                report = when (val outcome = leases.withLease(key) { removeOne(key, unreadable, removed) }) {
                    is Outcome.Removed -> report.copy(removed = report.removed + 1, bytes = report.bytes + outcome.record.bytes)
                    Outcome.Kept -> report.copy(kept = report.kept + 1)
                    Outcome.Failed -> report.copy(failed = report.failed + 1)
                }
            }
        } finally {
            // The files already removed get their days rebuilt, also when the caller was cancelled.
            if (removed.isNotEmpty() && !withContext(NonCancellable) { rebuild(removed) }) report = report.copy(rebuildFailed = true)
        }
        log(
            "old file cleanup: removed ${report.removed}, kept ${report.kept}, failed ${report.failed}, " +
                "stopped by sync ${report.stoppedBySync}, rebuild failed ${report.rebuildFailed}",
        )
        return report
    }

    /** Runs [afterRemoval] for the stations and days of [removed]. False when it threw. */
    private suspend fun rebuild(removed: List<ArchiveRecord>): Boolean {
        val days = HashMap<String, Set<Long>>()
        for (r in removed) days[r.station] = days[r.station].orEmpty()
        days.putAll(StationDays.of(removed))
        return try {
            afterRemoval(days)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("old file cleanup: rebuild failed: ${e::class.simpleName}")
            false
        }
    }

    /**
     * One file, under its lease. The checks may be cancelled, the removal itself runs to its end and adds
     * the record to [removed] there, so a cancellation that discards the result still rebuilds its days.
     */
    private suspend fun removeOne(key: ArchiveKey, unreadable: UnreadableLog, removed: MutableList<ArchiveRecord>): Outcome {
        val record = index.get(key) ?: return Outcome.Kept
        if (isLegacy(key, unreadable) != true) return Outcome.Kept
        val path = finalPath(key) ?: return Outcome.Kept
        return withContext(NonCancellable) { drop(key, record, path, removed) }
    }

    private suspend fun drop(key: ArchiveKey, record: ArchiveRecord, path: String, removed: MutableList<ArchiveRecord>): Outcome {
        try {
            index.remove(key)
            withContext(io) { tree.delete(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("old file cleanup: delete failed: ${e::class.simpleName}")
            restore(record)
            return Outcome.Failed
        }
        removed += record
        // The startup janitor may have adopted the file between the two steps. The file is gone, so its record goes too.
        try {
            if (index.get(key) != null && !store.exists(key)) index.remove(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("old file cleanup: re-adopt check failed: ${e::class.simpleName}")
        }
        return Outcome.Removed(record)
    }

    /** Puts [record] back after a failed delete. When that throws too, the startup janitor adopts the file again. */
    private suspend fun restore(record: ArchiveRecord) {
        try {
            index.putIfAbsent(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("old file cleanup: record restore failed: ${e::class.simpleName}")
        }
    }

    /**
     * The absolute path of the final copy, only when it sits below the Durable archive root. Anything
     * else is refused, so this class can never reach a cache, a partial or another role's files.
     */
    private fun finalPath(key: ArchiveKey): String? {
        val root = roots[StorageRole.Archive] ?: return null
        val path = "$root/${key.station}/${key.name}"
        if (!path.startsWith("$root/") || roots.classOf(path) != StorageClass.Durable) return null
        return path
    }

    /** Logs the class of the first unreadable footer of one run, so a broken archive does not flood the log. */
    private inner class UnreadableLog {
        private var logged = false

        fun note(e: Exception) {
            if (logged) return
            logged = true
            log("old file cleanup: unreadable footer: ${e::class.simpleName}")
        }
    }

    /** True or false from the footer, null when the file cannot be read. */
    private suspend fun isLegacy(key: ArchiveKey, unreadable: UnreadableLog): Boolean? = withContext(io) {
        try {
            ParquetFile.open(store.reader(key), key.name).use { UtcColumns.isLegacyLayout(it.metadata) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreadable.note(e)
            null
        }
    }
}
