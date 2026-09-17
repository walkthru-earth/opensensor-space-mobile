package earth.walkthru.aqsync.data.history

import android.util.Log
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the history index columns of `archived_file` (Room v4) from the files themselves.
 *
 * Two entry points: [SyncEngine] calls [indexed] right after a file is verified and committed, so a
 * freshly synced file is indexed in the same upsert; [backfill] walks rows that predate v4 (or whose
 * last attempt failed) in small batches, idempotently — it is safe to call from any screen that
 * needs the index and to be interrupted at any point.
 *
 * A file the reader refuses gets `indexError` and stays out of History, but remains in the archive
 * and in Files/Export untouched: the raw copy is the source of truth, the index only describes it.
 */
@Singleton
class HistoryIndexer @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
) {
    /** Index [file] (already verified) and return [row] with the index columns filled — never throws. */
    fun indexed(row: ArchivedFile, file: File, nowEpochMs: Long = System.currentTimeMillis()): ArchivedFile = try {
        val index = ParquetIndexer.index(ParquetFile.open(file))
        row.copy(
            indexedAtEpochMs = nowEpochMs,
            indexError = null,
            rows = index.rows,
            rowGroups = index.rowGroups,
            schemaVersion = index.schemaVersion,
            bootId = index.bootId,
            purpose = index.purpose,
            utcRows = index.utcRows,
            firstUtcNs = index.firstUtcNs,
            lastUtcNs = index.lastUtcNs,
            firstMonoUs = index.firstMonoUs,
            lastMonoUs = index.lastMonoUs,
            firstSeq = index.firstSeq,
            lastSeq = index.lastSeq,
            pmValidRows = index.pmValidRows,
        )
    } catch (e: ParquetFormatException) {
        row.copy(indexedAtEpochMs = nowEpochMs, indexError = e.message ?: "unreadable Parquet")
    } catch (e: java.io.IOException) {
        row.copy(indexedAtEpochMs = nowEpochMs, indexError = "I/O: ${e.message}")
    }

    /**
     * Indexes up to [limit] rows that need it. Returns how many rows were updated (0 = nothing left).
     * Rows whose previous attempt failed are retried once per call — a newer reader may accept them.
     */
    suspend fun backfill(limit: Int = 100): Int = withContext(Dispatchers.IO) {
        val pending = archivedFileDao.unindexed(limit)
        var done = 0
        for (row in pending) {
            val file = archive.fileFor(row.station, row.name)
            val updated = if (file.isFile) indexed(row, file) else row.copy(indexedAtEpochMs = System.currentTimeMillis(), indexError = "file missing from archive")
            // A failed retry of an already-failed row changes nothing; skip the write to keep the loop finite.
            if (row.indexError != null && updated.indexError != null) continue
            archivedFileDao.upsert(updated)
            done++
        }
        if (done > 0) Log.i(TAG, "indexed $done files (${pending.size} candidates)")
        done
    }

    /** Runs [backfill] until nothing is left, bounded, then the time-estimate pass. */
    suspend fun backfillAll(maxBatches: Int = 50) {
        repeat(maxBatches) { if (backfill() == 0) return }
        estimateTimes()
    }

    /**
     * §4.6: gives every unsynced file whose boot later received a clock an estimated UTC span, from
     * the index alone (no decoding). Idempotent; rows whose estimate is unchanged are not rewritten.
     * Returns how many rows changed.
     */
    suspend fun estimateTimes(): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (station in archivedFileDao.stations()) {
            val files = archivedFileDao.indexedForStation(station)
            val anchors = TimeEstimate.anchors(files)
            for (f in files) {
                val span = TimeEstimate.span(f, anchors)
                val anchor = f.bootId?.let { anchors[it] }
                val updated = if (span != null && anchor != null) {
                    f.copy(estFirstUtcNs = span.first, estLastUtcNs = span.second, estAnchorFile = anchor.sourceFile)
                } else if (f.estFirstUtcNs != null || f.estAnchorFile != null) {
                    f.copy(estFirstUtcNs = null, estLastUtcNs = null, estAnchorFile = null) // anchor gone or file now has UTC
                } else f
                if (updated != f) { archivedFileDao.upsert(updated); changed++ }
            }
        }
        if (changed > 0) Log.i(TAG, "estimated time for $changed files")
        changed
    }

    private companion object {
        const val TAG = "HistoryIndexer"
    }
}
