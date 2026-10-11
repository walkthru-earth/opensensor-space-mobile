package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ClockAnchorStore
import earth.walkthru.aqsync.archive.EmptyClockAnchorStore
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.sync.HivePartition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
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
    private val clockAnchors: ClockAnchorStore = EmptyClockAnchorStore,
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
            val index = ParquetFile.open(reader(), record.name).use { file ->
                val metadataStation = file.metadata.keyValueMetadata["station_id"]
                require(metadataStation == null || metadataStation == record.station) { "file metadata belongs to another station" }
                ParquetIndexer.index(file)
            }
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
                // Only the index columns are repair's. The engine may have changed the rest meanwhile.
                index.update(record.key) { latest -> withIndexColumns(latest, updated) }
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
     * Runs [backfillAll] until no record is left that was never indexed, at most [maxPasses] times. A
     * record put while a pass runs is caught by the next pass. Records whose last try failed do not
     * keep it going. A pass that throws ends it, the next trigger or start tries again.
     */
    suspend fun backfillUntilSettled(maxPasses: Int = 5) {
        var passes = 0
        do {
            try {
                backfillAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("index repair stopped: ${e.message}")
                return
            }
            passes++
        } while (passes < maxPasses && neverIndexedWaiting())
    }

    /**
     * Repairs whenever [index] holds a record that was never indexed, until cancelled. iOS runs it for
     * the life of the process, because its engine adds records without index columns. Changes that
     * arrive during a pass are conflated into one more check after it, never dropped. It watches
     * [ArchiveIndex.observeNeverIndexed], not every record.
     */
    suspend fun repairWhenWaiting() {
        index.observeNeverIndexed()
            .conflate()
            .collect { waiting -> if (waiting) backfillUntilSettled() }
    }

    /** [ArchiveIndex.unindexed] lists never indexed records first. */
    private suspend fun neverIndexedWaiting(): Boolean = index.unindexed(1).any { it.indexedAtEpochMs == null }

    /**
     * §4.6, gives every unsynced file whose boot later received a clock an estimated UTC span, from
     * indexed spans and persisted boot anchors. Footer evidence is imported once per file per
     * process; measurement columns are not decoded. Records whose estimate is unchanged are not
     * rewritten. Returns how many records changed.
     */
    suspend fun estimateTimes(): Int = mutex.withLock {
        withContext(io) {
            var changed = 0
            for (station in index.stations()) {
                val files = index.indexedForStation(station)
                captureClockAnchors(files)
                val anchors = TimeEstimate.anchors(files.map { it.info() }, clockAnchors.anchors(station))
                for (f in files) {
                    val updated = estimated(f, anchors)
                    if (updated != f) {
                        index.update(f.key) { latest -> latest.copy(estFirstUtcNs = updated.estFirstUtcNs, estLastUtcNs = updated.estLastUtcNs, estAnchorFile = updated.estAnchorFile) }
                        changed++
                    }
                }
            }
            if (changed > 0) log("estimated time for $changed files")
            changed
        }
    }

    private val scannedAnchors = HashSet<earth.walkthru.aqsync.archive.ArchiveKey>()

    /** Imports firmware footer evidence without modifying any raw row or path. */
    private suspend fun captureClockAnchors(files: List<ArchiveRecord>) {
        for (record in files) {
            if (record.key in scannedAnchors || !record.isTelemetry) continue
            try {
                val metadata = ParquetFile.open(store.reader(record.key), record.name).use { it.metadata }
                val boot = metadata.bootId ?: continue
                val producer = metadata.keyValueMetadata["station_id"]
                if (producer != null && producer != record.station) continue
                val values = metadata.keyValueMetadata
                val utc = values["reconciliation_anchor_utc_ns"]?.toLongOrNull()
                val mono = values["reconciliation_anchor_mono_us"]?.toLongOrNull()
                if (utc != null && mono != null) {
                    clockAnchors.put(earth.walkthru.aqsync.archive.ClockAnchor(record.station, boot, utc, mono, values["reconciliation_anchor_source"] ?: "firmware"))
                }
                scannedAnchors += record.key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("clock evidence unavailable for ${record.name}: ${e.message}")
            }
        }
    }

    companion object {
        /** The station inside the Hive path, which can differ from the archive namespace the file was saved under. */
        fun producerStation(name: String): String? = when (val partition = HivePartition.of(name)) {
            is HivePartition.Dated -> partition.station
            is HivePartition.Unsynced -> partition.station
            else -> null
        }

        /** [latest] with the index columns of [indexed], which repair filled from the file. Every other column stays as [latest] has it. */
        fun withIndexColumns(latest: ArchiveRecord, indexed: ArchiveRecord): ArchiveRecord = latest.copy(
            indexedAtEpochMs = indexed.indexedAtEpochMs,
            indexError = indexed.indexError,
            rows = indexed.rows,
            rowGroups = indexed.rowGroups,
            schemaVersion = indexed.schemaVersion,
            bootId = indexed.bootId,
            purpose = indexed.purpose,
            utcRows = indexed.utcRows,
            firstUtcNs = indexed.firstUtcNs,
            lastUtcNs = indexed.lastUtcNs,
            firstMonoUs = indexed.firstMonoUs,
            lastMonoUs = indexed.lastMonoUs,
            firstSeq = indexed.firstSeq,
            lastSeq = indexed.lastSeq,
            pmValidRows = indexed.pmValidRows,
        )

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
