package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.AtomicFiles
import kotlinx.coroutines.flow.Flow

/**
 * The bytes of the phone archive, laid out as [ArchiveLayout] says. Adapters do file I/O only,
 * every decision (what to resume, when a file is complete) is made by common callers. There is no
 * delete: a verified copy is immutable history, and the index record is its receipt (audit C, B6).
 *
 * Android adapts `FileArchive` (`filesDir`). iOS uses `IosArchiveStore` (Application Support, protection
 * class, backup exclusion, symlink checks), which keeps the finals of earlier iPhone builds in place.
 */
interface ArchiveStore {
    /** Byte counts of the resumable partials of [station], keyed by Hive name, for the planner. */
    suspend fun partialLengths(station: String): Map<String, Long>

    /**
     * Opens or resumes the partial of a file the device just OPENED. A partial written against another
     * [meta], a missing or malformed meta, or one longer than `meta.size` is discarded and restarts at 0.
     */
    suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter

    /** Removes the partial and its meta. No error when there is none. */
    suspend fun discardPartial(key: ArchiveKey)

    /** True when the verified copy of [key] is in the archive. */
    suspend fun exists(key: ArchiveKey): Boolean

    /** Random access to the final copy, or with [partial] to the download in progress, for [ArchiveVerifier] and `ParquetFile.open`. */
    fun reader(key: ArchiveKey, partial: Boolean = false): ByteReader

    /**
     * Moves a verified partial into the archive, atomically and durably ([AtomicFiles.commitNoReplace]).
     * Throws [AlreadyCommitted] when the final already exists, and leaves the partial in place: an
     * archived file is never replaced.
     */
    suspend fun commit(key: ArchiveKey)

    /** Every final file, of one station or of all. Partials, metas and legacy receipts are never listed. */
    suspend fun listFinal(station: String? = null): List<ArchiveKey>
}

/** Writes CHUNK payloads at their absolute offsets. Closing syncs to storage. */
interface PartialWriter : AutoCloseable {
    /** Bytes already on disk when the partial was opened, where the transfer continues. */
    val resumeFrom: Long

    fun writeAt(offset: Long, payload: ByteArray)

    fun length(): Long

    /** Forces written bytes to storage, before a window is acknowledged. */
    fun sync()
}

/**
 * The records of the archive, one per verified file, keyed by (station, name). Android adapts the Room
 * `archived_file` table (v10). iOS keeps one `.verified.json` receipt per file (`ReceiptArchiveIndex`)
 * until Room KMP gives it the same table.
 */
interface ArchiveIndex {
    /** Every record, ordered by name. */
    fun observeAll(): Flow<List<ArchiveRecord>>

    /** The records of one station, ordered by name. */
    fun observeStation(station: String): Flow<List<ArchiveRecord>>

    suspend fun get(key: ArchiveKey): ArchiveRecord?

    /** Inserts or replaces the record of (station, name). */
    suspend fun put(record: ArchiveRecord)

    /**
     * Replaces the record of [key] with [transform] of its latest value, when there is one. A writer
     * that owns only some columns (index repair) uses it so it never writes back a stale snapshot over
     * another writer's change. The default reads then writes. An adapter with a lock overrides it to do
     * both under that lock.
     */
    suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
        get(key)?.let { put(transform(it)) }
    }

    suspend fun remove(key: ArchiveKey)

    /** Records never indexed, then those whose last attempt failed, by name. */
    suspend fun unindexed(limit: Int): List<ArchiveRecord>

    /** Records with [ArchiveRecord.onTimeline] in `[fromNs, toNs)`, in [ArchiveRecord.TIMELINE_ORDER]. */
    suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord>

    /** Indexed records of one station, for the time-estimate pass. */
    suspend fun indexedForStation(station: String): List<ArchiveRecord>

    suspend fun stations(): List<String>
}
