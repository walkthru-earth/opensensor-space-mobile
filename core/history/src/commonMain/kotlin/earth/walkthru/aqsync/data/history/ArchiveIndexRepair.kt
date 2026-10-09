package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.sync.HivePartition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Fills and repairs the history columns of the [ArchiveIndex] from the verified files themselves, the
 * same pass on both phones. Android runs it at process start and after a sync. iOS runs it at app
 * start and whenever the engine adds a record without index columns.
 *
 * [indexed] describes one file. [backfill] walks records that were never indexed (or whose last try
 * failed) in small batches, idempotently, safe to interrupt anywhere. [estimateTimes] gives files
 * without UTC an estimated span once their boot has a clock (§4.6). A file the reader refuses gets
 * `indexError` and stays out of History. It remains in the archive untouched, the raw copy is the
 * source of truth and the index only describes it.
 */
class ArchiveIndexRepair(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** [record] with its index columns filled from [reader]. Never throws. */
    fun indexed(record: ArchiveRecord, reader: () -> ByteReader, nowEpochMs: Long = this.nowEpochMs()): ArchiveRecord = try {
        val producer = producerStation(record.name)
        if (producer != null && producer != record.station) {
            record.copy(
                indexedAtEpochMs = nowEpochMs,
                indexError = "file belongs to station $producer, raw bytes kept, excluded from the history of station ${record.station}",
            )
        } else {
            val index = ParquetIndexer.index(ParquetFile.open(reader(), record.name))
            record.copy(
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
        }
    } catch (e: ParquetFormatException) {
        record.copy(indexedAtEpochMs = nowEpochMs, indexError = e.message ?: "unreadable Parquet")
    } catch (e: Exception) {
        record.copy(indexedAtEpochMs = nowEpochMs, indexError = "I/O: ${e.message}")
    }

    /**
     * Indexes up to [limit] records that need it and returns how many were updated (0 means nothing
     * is left). A record whose previous attempt failed is retried once per call, a newer reader may
     * accept it.
     */
    suspend fun backfill(limit: Int = 100): Int = mutex.withLock {
        withContext(io) {
            val pending = index.unindexed(limit)
            var done = 0
            for (record in pending) {
                val updated = if (store.exists(record.key)) {
                    indexed(record, { store.reader(record.key) })
                } else {
                    record.copy(indexedAtEpochMs = nowEpochMs(), indexError = "file missing from archive")
                }
                // A failed retry of an already failed record changes nothing. Skip the write so the loop ends.
                if (record.indexError != null && updated.indexError != null) continue
                index.put(updated)
                done++
            }
            if (done > 0) log("indexed $done files (${pending.size} candidates)")
            done
        }
    }

    /** Runs [backfill] until nothing is left, bounded, then [estimateTimes]. */
    suspend fun backfillAll(maxBatches: Int = 50) {
        var remaining = maxBatches
        while (remaining > 0) {
            if (backfill() == 0) break
            remaining--
        }
        estimateTimes()
    }

    /**
     * §4.6, gives every unsynced file whose boot later received a clock an estimated UTC span, from
     * the index alone (no decoding). Idempotent, records whose estimate is unchanged are not
     * rewritten. Returns how many records changed.
     */
    suspend fun estimateTimes(): Int = withContext(io) {
        var changed = 0
        for (station in index.stations()) {
            val files = index.indexedForStation(station)
            val anchors = TimeEstimate.anchors(files.map { it.info() })
            for (f in files) {
                val updated = estimated(f, anchors)
                if (updated != f) {
                    index.put(updated)
                    changed++
                }
            }
        }
        if (changed > 0) log("estimated time for $changed files")
        changed
    }

    companion object {
        /** The station inside the Hive path, which can differ from the archive namespace the file was saved under. */
        fun producerStation(name: String): String? = when (val partition = HivePartition.of(name)) {
            is HivePartition.Dated -> partition.station
            is HivePartition.Unsynced -> partition.station
            else -> null
        }

        /** [file] with its estimate set from [anchors], or cleared when the anchor is gone or the file now has UTC. */
        fun estimated(file: ArchiveRecord, anchors: Map<String, TimeEstimate.Anchor>): ArchiveRecord {
            val span = TimeEstimate.span(file.info(), anchors)
            val anchor = file.bootId?.let { anchors[it] }
            return when {
                span != null && anchor != null -> file.copy(estFirstUtcNs = span.first, estLastUtcNs = span.second, estAnchorFile = anchor.sourceFile)
                file.estFirstUtcNs != null || file.estAnchorFile != null -> file.copy(estFirstUtcNs = null, estLastUtcNs = null, estAnchorFile = null)
                else -> file
            }
        }
    }
}
