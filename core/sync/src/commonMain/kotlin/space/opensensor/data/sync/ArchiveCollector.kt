package space.opensensor.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.ble.DeviceErrorException
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.ListCursor
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.storage.StorageBudget
import space.opensensor.sync.nowEpochMs
import kotlin.time.Duration.Companion.hours
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
 * The listing one collection plans from. [whole] means it named every file the device holds, from
 * plain `LIST` or a `full` `LIST_SINCE`, so a name missing from it proves the file is gone. [sent]
 * is the cursor `LIST_SINCE` was asked with and [next] the one its `LIST_SINCE_END` returned, both
 * null after plain `LIST`.
 */
class SyncListing(
    val files: List<Frame.File>,
    val end: Frame.ListEnd,
    val whole: Boolean,
    val sent: ListCursor? = null,
    val next: ListCursor? = null,
)

/**
 * One collection over an open [DeviceSession]: a listing ([list]), plan against the archive, then
 * every missing file through the [SyncEngine], oldest dated partition first. The session is a view on
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
 *
 * [list] asks `LIST_SINCE` with the station's saved cursor in [cursors] when the board advertises it
 * (STATUS `ls`), and plain `LIST` otherwise. [settle] saves the next cursor only after a run that
 * left nothing worth listing again. See docs/sync-engine.md.
 */
class ArchiveCollector(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val engine: SyncEngine,
    private val budget: StorageBudget,
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    /** A fresh planner snapshot. Adapters can project identity columns without loading history. */
    private val archivedIdentities: suspend (String) -> List<ArchivedIdentity> = { thing ->
        index.observeThing(thing).first().map { ArchivedIdentity(it.thing, it.name, it.bytes, it.crc32) }
    },
    private val cursors: ListCursors = ListCursors.None,
    private val clock: () -> Long = ::nowEpochMs,
) {
    /** The engine's coordinator, the one owner of this archive's admission, locks and tombstones. */
    val coordinator: DeviceCoordinator get() = engine.coordinator
    private val pruned: PrunedFiles get() = engine.coordinator.pruned

    suspend fun collect(
        session: DeviceSession,
        thing: String,
        sourceDevice: String,
        maxRead: Int,
        onlyName: String? = null,
        onProgress: (CollectionProgress) -> Unit,
    ): CollectionSummary {
        val listing = list(session, thing, onlyName)
        val planned = plan(thing, listing.files, onlyName, listing.whole)
        return transfer(session, planned, thing, sourceDevice, maxRead, onProgress = onProgress).also { settle(thing, listing, it) }
    }

    /**
     * The listing for one run. [onlyName] and a board without `LIST_SINCE` take plain `LIST`. Else
     * `LIST_SINCE` goes out with the saved cursor, or with [ListCursor.ZERO] for a whole listing
     * when there is none, the card changed (`ls` differs from its mount epoch or is 0), this phone
     * holds no file of [thing], or the audit is due. `ERROR` 11 falls back to `LIST`, and `ERROR` 1
     * on a saved cursor asks again with the zero cursor. Any other failure throws as `LIST` does.
     */
    suspend fun list(session: DeviceSession, thing: String, onlyName: String? = null): SyncListing {
        if (onlyName != null || session.payloadMax < FrameCodec.LIST_SINCE_END_BYTES) return plainList(session)
        val epoch = (session.latestStatus ?: session.freshStatus()?.first)?.listSinceEpoch ?: return plainList(session)
        val saved = savedCursor(thing)
        val cursor = if (saved == null || epoch == 0L || saved.cursor.mountEpoch != epoch || auditDue(saved) || !holdsFiles(thing)) {
            ListCursor.ZERO
        } else {
            saved.cursor
        }
        return try {
            listSince(session, cursor)
        } catch (e: DeviceErrorException) {
            when {
                e.frame.code == ProtocolError.UNKNOWN_OPCODE.code -> plainList(session)
                e.frame.code == ProtocolError.MALFORMED_REQUEST.code && !cursor.isZero -> listSince(session, ListCursor.ZERO)
                else -> throw e
            }
        }
    }

    private suspend fun plainList(session: DeviceSession): SyncListing {
        val (files, end) = session.list()
        return SyncListing(files, end, whole = true)
    }

    private suspend fun listSince(session: DeviceSession, cursor: ListCursor): SyncListing {
        val (files, end) = session.listSince(cursor)
        return SyncListing(files, end.toListEnd(), whole = cursor.isZero || end.full, sent = cursor, next = end.next)
    }

    /** A cursor that cannot be read counts as none, so the run takes a whole listing. */
    private suspend fun savedCursor(thing: String): ListCursorState? = try {
        cursors.get(thing)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** An archive this phone cleared needs every file again, so an unreadable index counts as empty. */
    private suspend fun holdsFiles(thing: String): Boolean = try {
        thing in index.things()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Once a day, or every [AUDIT_EVERY_SYNCS] runs, a whole listing catches what a cursor can miss. */
    private fun auditDue(saved: ListCursorState): Boolean {
        val age = clock() - saved.wholeAtEpochMs
        return age !in 0 until AUDIT_INTERVAL_MS || saved.sinceWhole + 1 >= AUDIT_EVERY_SYNCS
    }

    /**
     * Saves the next cursor of a `LIST_SINCE` run once [summary] left nothing a later listing must
     * name again. Only an unsafe name or a local conflict may stay unsaved, a retry of those cannot
     * succeed. A run that throws never gets here, so the board lists the same files next time. A
     * failed write keeps the old cursor, which only lists more.
     */
    suspend fun settle(thing: String, listing: SyncListing, summary: CollectionSummary) {
        val next = listing.next ?: return
        if (summary.notSaved.any { it.problem != FileProblem.INVALID_ENTRY && it.problem != FileProblem.LOCAL_CONFLICT }) return
        try {
            val state = if (listing.whole) {
                ListCursorState(thing, next, clock(), 0)
            } else {
                val saved = cursors.get(thing) ?: return
                saved.copy(cursor = next, sinceWhole = saved.sinceWhole + 1)
            }
            cursors.put(state)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept for the next run, which lists from the older cursor.
        }
    }

    /**
     * Plans one run from a fresh listing against the archive and reserves room for it.
     * [onlyName] narrows the run to that listed file. [whole] is false for an incremental
     * `LIST_SINCE`, which proves nothing about names it does not carry. Throws
     * [InsufficientSpaceException] when the missing bytes do not fit, before anything is opened.
     */
    suspend fun plan(thing: String, files: List<Frame.File>, onlyName: String? = null, whole: Boolean = true): PlannedCollection {
        val scopedFiles = if (onlyName == null) files else files.filter { it.name == onlyName }
        if (onlyName != null && scopedFiles.isEmpty()) {
            return PlannedCollection(SyncPlan(emptyList(), emptyList()), listOf(FileNotSaved(onlyName, FileProblem.REFUSED)), unlisted = true)
        }
        val archived = archivedIdentities(thing)
        val plan = SyncPlanner.plan(thing, scopedFiles, archived, store.partialLengths(thing), pruned.forThing(thing))
        // Only a whole listing proves a name is gone. A record that cannot be dropped stays until the
        // next run, which never stops this one, since the device does not list that file now.
        if (onlyName == null && whole) plan.unlistedRemoved.forEach { forgetRemoved(it) }
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
        thing: String,
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
            val outcome = engine.download(session, thing, sourceDevice, entry.name, entry.bytes, maxRead, onSettled = { settled ->
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
 * One run's plan from a fresh listing. [notSaved] holds the entries reported without a transfer.
 * [unlisted] means the one selected file was not in the listing, so nothing runs.
 */
class PlannedCollection(val plan: SyncPlan, val notSaved: List<FileNotSaved>, val unlisted: Boolean = false)

/** Audit after a day since the last whole listing. */
internal val AUDIT_INTERVAL_MS: Long = 24.hours.inWholeMilliseconds

/** Audit on every tenth run, so nine incremental runs follow a whole listing. */
internal const val AUDIT_EVERY_SYNCS: Int = 10
