package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.ClockAnchorStore
import space.opensensor.archive.EmptyClockAnchorStore
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetFormatException
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.HivePartition
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
 * source of truth and the index only describes it. Each row update runs under the thing gate of the
 * [coordinator], so a row the old file cleanup removed is never written back.
 */
class ArchiveIndexRepair(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val log: (String) -> Unit = {},
    private val clockAnchors: ClockAnchorStore = EmptyClockAnchorStore,
    /** The archive's one coordinator. Each row update runs under its thing gate. */
    private val coordinator: DeviceCoordinator = DeviceCoordinator(),
    /** Resolves each file's board template for its valid row count. */
    private val templates: FileTemplates = FileTemplates.SERVED,
    /**
     * Gets the footer `template.sha256` of each file that indexes, [BoardDescriptors.confirm] in the
     * app. Its failure is logged and never fails the index.
     */
    private val onTemplateDigest: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * [record] with its index columns filled from [reader]. Hands the footer `template.sha256` of a file
     * that indexes to [onTemplateDigest]. Never throws, except to cancel.
     */
    suspend fun indexed(record: ArchiveRecord, reader: () -> ByteReader, nowEpochMs: Long = this.nowEpochMs()): ArchiveRecord {
        var templateDigest: String? = null
        val indexed = indexRecord(record, reader, nowEpochMs) { templateDigest = it }
        templateDigest?.let { digest ->
            try {
                onTemplateDigest(digest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("template digest check failed: ${e::class.simpleName}")
            }
        }
        return indexed
    }

    private fun indexRecord(record: ArchiveRecord, reader: () -> ByteReader, nowEpochMs: Long, digest: (String) -> Unit): ArchiveRecord = try {
        val producer = producerThing(record.name)
        if (producer != null && producer != record.thing) {
            record.copy(
                indexedAtEpochMs = nowEpochMs,
                indexError = "file belongs to thing $producer, raw bytes kept, excluded from the history of thing ${record.thing}",
            )
        } else {
            val index = ParquetFile.open(reader(), record.name).use { file ->
                val footer = file.metadata.footer
                require(footer == null || footer.thingId == record.thing) { "file metadata belongs to another thing" }
                ParquetIndexer.index(file, templates).also { footer?.templateSha256?.let(digest) }
            }
            record.copy(
                indexedAtEpochMs = nowEpochMs,
                indexError = null,
                rows = index.rows,
                rowGroups = index.rowGroups,
                bootId = index.bootId,
                purpose = index.purpose,
                utcRows = index.utcRows,
                firstUtcNs = index.firstUtcNs,
                lastUtcNs = index.lastUtcNs,
                firstMonoUs = index.firstMonoUs,
                lastMonoUs = index.lastMonoUs,
                firstSeq = index.firstSeq,
                lastSeq = index.lastSeq,
                dictionarySha256 = index.dictionarySha256,
                templateSha256 = index.templateSha256,
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
                coordinator.withThing(record.thing) { index.update(record.key) { latest -> withIndexColumns(latest, updated) } }
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

    /** [ArchiveIndex.unindexed] lists never indexed records first. */
    private suspend fun neverIndexedWaiting(): Boolean = index.unindexed(1).any { it.awaitsIndex }

    /**
     * §4.6, gives every unsynced file whose boot later received a clock an estimated UTC span, from
     * indexed spans and persisted boot anchors. The footer carries no clock anchor, the rows and
     * the sync receipts do. Measurement columns are not decoded. Records whose estimate is unchanged are not
     * rewritten. Returns how many records changed.
     */
    suspend fun estimateTimes(): Int = mutex.withLock {
        withContext(io) {
            var changed = 0
            for (thing in index.things()) {
                val files = index.indexedForThing(thing)
                val anchors = TimeEstimate.anchors(files.map { it.info() }, clockAnchors.anchors(thing))
                for (f in files) {
                    val updated = estimated(f, anchors)
                    if (updated != f) {
                        coordinator.withThing(f.thing) {
                            index.update(f.key) { latest -> latest.copy(estFirstUtcNs = updated.estFirstUtcNs, estLastUtcNs = updated.estLastUtcNs, estAnchorFile = updated.estAnchorFile) }
                        }
                        changed++
                    }
                }
            }
            if (changed > 0) log("estimated time for $changed files")
            changed
        }
    }

    companion object {
        /** The thing inside the Hive path, which can differ from the archive namespace the file was saved under. */
        fun producerThing(name: String): String? = when (val partition = HivePartition.of(name)) {
            is HivePartition.Dated -> partition.thing
            is HivePartition.Unsynced -> partition.thing
            else -> null
        }

        /** [latest] with the index columns of [indexed], which repair filled from the file. Every other column stays as [latest] has it. */
        fun withIndexColumns(latest: ArchiveRecord, indexed: ArchiveRecord): ArchiveRecord = latest.copy(
            indexedAtEpochMs = indexed.indexedAtEpochMs,
            indexError = indexed.indexError,
            rows = indexed.rows,
            rowGroups = indexed.rowGroups,
            bootId = indexed.bootId,
            purpose = indexed.purpose,
            utcRows = indexed.utcRows,
            firstUtcNs = indexed.firstUtcNs,
            lastUtcNs = indexed.lastUtcNs,
            firstMonoUs = indexed.firstMonoUs,
            lastMonoUs = indexed.lastMonoUs,
            firstSeq = indexed.firstSeq,
            lastSeq = indexed.lastSeq,
            dictionarySha256 = indexed.dictionarySha256,
            templateSha256 = indexed.templateSha256,
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
