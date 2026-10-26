package space.opensensor.data.history

import android.util.Log
import kotlinx.coroutines.Dispatchers
import space.opensensor.archive.ClockAnchor
import space.opensensor.archive.FileClockAnchorStore
import space.opensensor.data.archive.FileArchiveStore
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.toEntity
import space.opensensor.data.archive.toRecord
import space.opensensor.data.history.parquet.LocalFileReader
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileArchive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's entry to the shared [ArchiveIndexRepair] over Room's `archived_file` and the files in
 * `filesDir`. The sync engine calls [indexed] right after a file is verified and committed, so a
 * fresh file is indexed in the same upsert. [backfill] walks records that predate the index or whose
 * last attempt failed. A file the reader refuses gets `indexError` and stays archived untouched.
 */
@Singleton
class HistoryIndexer internal constructor(
    archive: FileArchive,
    archivedFileDao: ArchivedFileDao,
    coordinator: DeviceCoordinator,
    /** Resolves each file's board template from its footer, for the index and for [HistoryDeriver]. */
    val templates: FileTemplates = FileTemplates.BUNDLED,
    /** Gets each indexed file's footer `template.sha256`, see [BoardDescriptors.confirm]. */
    onTemplateDigest: suspend (String) -> Unit = {},
) {
    @Inject constructor(archive: FileArchive, archivedFileDao: ArchivedFileDao, coordinator: DeviceCoordinator, boardDescriptors: BoardDescriptors) :
        this(archive, archivedFileDao, coordinator, FileTemplates.BUNDLED, boardDescriptors::confirm)

    val clockAnchors = FileClockAnchorStore(File(archive.root.parentFile, "clock-anchors"))

    /** Only the durable receipt; reconciliation stays in a cancellable history pass. */
    suspend fun persistClockAnchor(thing: String, boot: String, utcNs: Long, monotonicUs: Long) {
        clockAnchors.put(ClockAnchor(thing, boot, utcNs, monotonicUs))
    }

    /** The shared repair, which the [HistoryOrchestrator] runs as its backfill. */
    val repair = ArchiveIndexRepair(
        store = FileArchiveStore(archive, Dispatchers.IO),
        index = RoomArchiveIndex(archivedFileDao),
        io = Dispatchers.IO,
        clockAnchors = clockAnchors,
        coordinator = coordinator,
        log = { Log.i(TAG, it) },
        templates = templates,
        onTemplateDigest = onTemplateDigest,
    )

    /** [row] with the index columns filled from [file] (already verified). Never throws. */
    suspend fun indexed(row: ArchivedFile, file: File, nowEpochMs: Long = System.currentTimeMillis()): ArchivedFile =
        repair.indexed(row.toRecord(), { LocalFileReader(file) }, nowEpochMs).toEntity()

    /** Indexes up to [limit] rows that need it. Returns how many rows were updated (0 means nothing is left). */
    suspend fun backfill(limit: Int = 100): Int = repair.backfill(limit)

    /** Runs [backfill] until nothing is left, bounded, then the time-estimate pass. */
    suspend fun backfillAll(maxBatches: Int = 50) = repair.backfillAll(maxBatches)

    /**
     * §4.6 estimated spans for unsynced files whose boot later received a clock. Returns how many rows
     * changed. Call it only inside a History pass, as [HistoryDeriver.deriveStale] does, since a derive
     * reads these columns.
     */
    suspend fun estimateTimes(): Int = repair.estimateTimes()

    private companion object {
        const val TAG = "HistoryIndexer"
    }
}
