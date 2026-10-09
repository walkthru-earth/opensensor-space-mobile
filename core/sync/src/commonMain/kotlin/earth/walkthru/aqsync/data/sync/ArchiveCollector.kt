package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.ble.DeviceSession
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
    ;

    companion object {
        fun of(outcome: FileOutcome): FileProblem? = when (outcome) {
            is FileOutcome.Synced -> null
            is FileOutcome.Mismatch -> CHECK_FAILED
            is FileOutcome.DeviceError -> REFUSED
            is FileOutcome.Failed -> FAILED
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
)

/** Every listed file was saved, already on this phone, or is in [notSaved]. */
data class CollectionSummary(val files: Int, val notSaved: List<FileNotSaved>) {
    val complete: Boolean get() = notSaved.isEmpty()
}

/**
 * One full collection over an open [DeviceSession]: `LIST`, plan against the archive, then every
 * missing file through the [SyncEngine], oldest dated partition first. A file that cannot be saved
 * fails alone and is reported in the summary. A link or protocol fault ends the run by throwing,
 * and the partials written so far resume on the next run.
 *
 * The collector never asks the device to delete, rename or rewrite a file.
 */
class ArchiveCollector(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val engine: SyncEngine,
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    suspend fun collect(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        maxRead: Int,
        onProgress: (CollectionProgress) -> Unit,
    ): CollectionSummary {
        val (files, _) = session.list()
        val archived = index.observeStation(station).first().map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
        val plan = SyncPlanner.plan(station, files, archived, store.partialLengths(station))
        val notSaved = plan.entries.mapNotNullTo(ArrayList()) { entry ->
            when (entry.state) {
                FileSyncState.INVALID_NAME -> FileNotSaved(entry.name, FileProblem.INVALID_ENTRY)
                FileSyncState.MISMATCH -> FileNotSaved(entry.name, FileProblem.LOCAL_CONFLICT)
                else -> null
            }
        }
        val total = plan.entries.size
        var done = total - plan.toDownload.size
        var received = 0L
        var fileBytes = 0L
        val meter = RateMeter(time)
        onProgress(CollectionProgress(done, total, 0, 0, 0.0))
        for (entry in plan.toDownload) {
            val before = received
            val outcome = engine.download(session, station, sourceDevice, entry.name, entry.bytes, maxRead) { p ->
                received = before + (p.bytesDone - p.resumedFrom).coerceAtLeast(0)
                fileBytes = p.bytesDone
                onProgress(CollectionProgress(done, total, fileBytes, received, meter.update(received)))
            }
            FileProblem.of(outcome)?.let { notSaved += FileNotSaved(entry.name, it) }
            done++
            onProgress(CollectionProgress(done, total, fileBytes, received, meter.update(received)))
        }
        return CollectionSummary(total, notSaved)
    }
}
