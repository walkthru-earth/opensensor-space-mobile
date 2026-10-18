package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.storage.StorageBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.time.TimeSource

/** Why one listed file was not saved. The collection went on with the next file. */
enum class FileProblem {
    /** The listing named an unsafe path. Nothing was stored. */
    INVALID_ENTRY,

    /** This phone already holds a different copy under that name. The copy was preserved. */
    LOCAL_CONFLICT,

    /** OPENED disagreed with the listing, or the bytes failed size, CRC-32 or `PAR1`. */
    CHECK_FAILED,

    /** The device answered the request with `ERROR` or a failed `READ_END`. */
    REFUSED,

    /** The bytes kept arriving incomplete or stopped short. Saved bytes resume next time. */
    FAILED,

    /** This phone could not write, move or record the file. Bytes already held stay. */
    STORAGE,
    ;

    companion object {
        fun of(outcome: FileOutcome): FileProblem? = when (outcome) {
            is FileOutcome.Synced -> null
            is FileOutcome.Mismatch -> CHECK_FAILED
            is FileOutcome.DeviceError -> REFUSED
            is FileOutcome.Failed -> if (outcome.storage) STORAGE else FAILED
            is FileOutcome.Removed -> null
        }
    }
}

data class FileNotSaved(val name: String, val problem: FileProblem)

/**
 * Progress of one collection. [fileBytes] is what this phone holds of the file in transfer.
 * [receivedBytes] counts bytes this run received, never resumed or reused bytes, so
 * [bytesPerSecond] is the link's throughput.
 */
data class CollectionProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val fileBytes: Long,
    val receivedBytes: Long,
    val bytesPerSecond: Double,
    /** Bytes still needed when LIST planned this run, after any reusable partials. */
    val bytesTotal: Long = 0,
    /** The file currently in transfer, absent before transfer and after its outcome settles. */
    val current: FileProgress? = null,
)

/**
 * Every scoped file was saved, already on this phone, or is in [notSaved]. [files] is the scoped
 * count; [newFiles] and [newBytes] count only newly archived [FileOutcome.Synced] results, including
 * resumed bytes and excluding destinations reused after a concurrent writer committed them.
 * [outcomes] contains the files actually passed through the transfer engine.
 */
data class CollectionSummary(
    val files: Int,
    val notSaved: List<FileNotSaved>,
    val newFiles: Int = 0,
    val newBytes: Long = 0,
    val outcomes: List<FileOutcome> = emptyList(),
) {
    val complete: Boolean get() = notSaved.isEmpty()
}

/**
 * One full collection over an open [DeviceSession]: `LIST`, plan against the archive, then every
 * missing file through the [SyncEngine], oldest dated partition first. The session is a view on
 * its link and holds no protocol state, so a view made per run shares the link's drain and owed
 * `CLOSE`. A file that cannot be saved fails alone and is reported in the summary. A link,
 * protocol or session fault (a refused token, a busy sensor) ends the run by throwing, and the
 * partials written so far resume on the next run.
 *
 * Before the first `OPEN` the planned bytes are asked of [budget] ([requireRoom]). A run that
 * does not fit throws [InsufficientSpaceException] and opens nothing. A file that fails to store checks the
 * room again, and when it ran out the run ends the same way, keeping the partial for resume.
 *
 * A file the user removed from this phone in Settings (the coordinator's [DeviceCoordinator.pruned])
 * is skipped while the device still lists it at the same size, and the engine checks again under the
 * file's writer lease, so a removal that lands after this plan still wins. A whole listing that no longer has the name forgets the record, so a
 * later file under that name downloads. The collector never asks the device to delete, rename or
 * rewrite a file.
 */
class ArchiveCollector(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val engine: SyncEngine,
    private val budget: StorageBudget,
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    /** A fresh planner snapshot. Adapters can project identity columns without loading history. */
    private val archivedIdentities: suspend (String) -> List<ArchivedIdentity> = { station ->
        index.observeStation(station).first().map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
    },
) {
    /** The engine's coordinator, the one owner of this archive's admission, locks and tombstones. */
    val coordinator: DeviceCoordinator get() = engine.coordinator
    private val pruned: PrunedFiles get() = engine.coordinator.pruned

    suspend fun collect(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        maxRead: Int,
        onlyName: String? = null,
        onProgress: (CollectionProgress) -> Unit,
    ): CollectionSummary {
        val (files, _) = session.list()
        val planned = plan(station, files, onlyName)
        return transfer(session, planned, station, sourceDevice, maxRead, onProgress = onProgress)
    }

    /**
     * Plans one run from a fresh `LIST` result against the archive and reserves room for it.
     * [onlyName] narrows the run to that listed file. Throws [InsufficientSpaceException] when
     * the missing bytes do not fit, before anything is opened.
     */
    suspend fun plan(station: String, files: List<Frame.File>, onlyName: String? = null): PlannedCollection {
        val scopedFiles = if (onlyName == null) files else files.filter { it.name == onlyName }
        if (onlyName != null && scopedFiles.isEmpty()) {
            return PlannedCollection(SyncPlan(emptyList(), emptyList()), listOf(FileNotSaved(onlyName, FileProblem.REFUSED)), unlisted = true)
        }
        val archived = archivedIdentities(station)
        val plan = SyncPlanner.plan(station, scopedFiles, archived, store.partialLengths(station), pruned.forStation(station))
        // Only a whole listing proves a name is gone. A record that cannot be dropped stays until the
        // next run, which never stops this one, since the device does not list that file now.
        if (onlyName == null) plan.unlistedRemoved.forEach { forgetRemoved(it) }
        val notSaved = plan.entries.mapNotNull { entry ->
            when (entry.state) {
                FileSyncState.INVALID_NAME -> FileNotSaved(entry.name, FileProblem.INVALID_ENTRY)
                FileSyncState.MISMATCH -> FileNotSaved(entry.name, FileProblem.LOCAL_CONFLICT)
                else -> null
            }
        }
        budget.requireRoom(plan.missingBytes)
        return PlannedCollection(plan, notSaved)
    }

    private suspend fun forgetRemoved(file: PrunedFile) {
        try {
            pruned.remove(file.key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept for the next whole listing.
        }
    }

    /**
     * Every missing file of [planned] through the [SyncEngine], in plan order. [onOutcome] records
     * settled files even when a later link failure throws before this method returns a summary.
     */
    suspend fun transfer(
        session: DeviceSession,
        planned: PlannedCollection,
        station: String,
        sourceDevice: String,
        maxRead: Int,
        onOutcome: (FileOutcome) -> Unit = {},
        onProgress: (CollectionProgress) -> Unit,
    ): CollectionSummary {
        val plan = planned.plan
        if (planned.unlisted) return CollectionSummary(1, planned.notSaved)
        val notSaved = ArrayList(planned.notSaved)
        val total = plan.entries.size
        val bytesTotal = plan.missingBytes
        var done = total - plan.toDownload.size
        var received = 0L
        var fileBytes = 0L
        val outcomes = ArrayList<FileOutcome>()
        val meter = RateMeter(time)
        onProgress(CollectionProgress(done, total, 0, 0, 0.0, bytesTotal))
        for ((at, entry) in plan.toDownload.withIndex()) {
            val before = received
            val outcome = engine.download(session, station, sourceDevice, entry.name, entry.bytes, maxRead, onSettled = { settled ->
                outcomes += settled
                onOutcome(settled)
            }) { p ->
                received = before + (p.bytesDone - p.resumedFrom).coerceAtLeast(0)
                fileBytes = p.bytesDone
                onProgress(CollectionProgress(done, total, fileBytes, received, meter.update(received), bytesTotal, current = p))
            }
            val problem = FileProblem.of(outcome)
            problem?.let { notSaved += FileNotSaved(entry.name, it) }
            done++
            onProgress(CollectionProgress(done, total, fileBytes, received, meter.update(received), bytesTotal))
            if (problem == FileProblem.STORAGE) {
                // A full disk fails every later file the same way, so it ends the run here. The
                // failed file counts from its planned resume point, as on Android.
                budget.requireRoom(plan.toDownload.drop(at).sumOf { it.bytes - it.partialBytes })
            }
        }
        val synced = outcomes.filterIsInstance<FileOutcome.Synced>().filter { !it.alreadyArchived }
        return CollectionSummary(total, notSaved, synced.size, synced.sumOf { it.bytes }, outcomes)
    }
}

/**
 * One run's plan from a fresh `LIST`. [notSaved] holds the entries reported without a transfer.
 * [unlisted] means the one selected file was not in the listing, so nothing runs.
 */
class PlannedCollection(val plan: SyncPlan, val notSaved: List<FileNotSaved>, val unlisted: Boolean = false)
