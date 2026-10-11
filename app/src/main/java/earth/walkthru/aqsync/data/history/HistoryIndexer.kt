package earth.walkthru.aqsync.data.history

import android.util.Log
import earth.walkthru.aqsync.archive.ClockAnchor
import earth.walkthru.aqsync.archive.FileClockAnchorStore
import earth.walkthru.aqsync.data.archive.FileArchiveStore
import earth.walkthru.aqsync.data.archive.RoomArchiveIndex
import earth.walkthru.aqsync.data.archive.toEntity
import earth.walkthru.aqsync.data.archive.toRecord
import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.Dispatchers
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
class HistoryIndexer @Inject constructor(
    archive: FileArchive,
    archivedFileDao: ArchivedFileDao,
) {
    val clockAnchors = FileClockAnchorStore(File(archive.root.parentFile, "clock-anchors"))

    suspend fun recordClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {
        clockAnchors.put(ClockAnchor(station, boot, utcNs, monotonicUs))
        repair.estimateTimes()
    }

    private val repair = ArchiveIndexRepair(
        store = FileArchiveStore(archive, Dispatchers.IO),
        index = RoomArchiveIndex(archivedFileDao),
        io = Dispatchers.IO,
        clockAnchors = clockAnchors,
        log = { Log.i(TAG, it) },
    )

    /** [row] with the index columns filled from [file] (already verified). Never throws. */
    fun indexed(row: ArchivedFile, file: File, nowEpochMs: Long = System.currentTimeMillis()): ArchivedFile =
        repair.indexed(row.toRecord(), { LocalFileReader(file) }, nowEpochMs).toEntity()

    /** Indexes up to [limit] rows that need it. Returns how many rows were updated (0 means nothing is left). */
    suspend fun backfill(limit: Int = 100): Int = repair.backfill(limit)

    /** Runs [backfill] until nothing is left, bounded, then the time-estimate pass. */
    suspend fun backfillAll(maxBatches: Int = 50) = repair.backfillAll(maxBatches)

    /** §4.6 estimated spans for unsynced files whose boot later received a clock. Returns how many rows changed. */
    suspend fun estimateTimes(): Int = repair.estimateTimes()

    private companion object {
        const val TAG = "HistoryIndexer"
    }
}
