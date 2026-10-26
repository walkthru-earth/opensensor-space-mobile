package space.opensensor.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.storage.AlreadyCommitted
import space.opensensor.storage.AtomicFiles

/**
 * The bytes of the phone archive, laid out as [ArchiveLayout] says. Adapters do file I/O only,
 * every decision (what to resume, when a file is complete) is made by common callers. There is no
 * delete. A verified copy is immutable history, and the index record is its receipt (audit C, B6).
 * The one exception is the user's old format cleanup in Settings, `LegacyFileCleanup`, which removes
 * the record and then the final file through `StorageTree`, and leaves a [PrunedFile] so sync skips it.
 *
 * Android adapts `FileArchive` (`filesDir`). iOS uses `IosArchiveStore` (Application Support, protection
 * class, backup exclusion, symlink checks), which keeps the finals of earlier iPhone builds in place.
 */
interface ArchiveStore {
    /** Byte counts of the resumable partials of [thing], keyed by Hive name, for the planner. */
    suspend fun partialLengths(thing: String): Map<String, Long>

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

    /** Every final file, of one thing or of all. Partials, metas and legacy receipts are never listed. */
    suspend fun listFinal(thing: String? = null): List<ArchiveKey>
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
 * The records of the archive, one per verified file, keyed by (thing, name). Both phones adapt the
 * Room `archived_file` table through `RoomArchiveIndex` in `:core:database`.
 */
interface ArchiveIndex {
    /** Every record, ordered by name. */
    fun observeAll(): Flow<List<ArchiveRecord>>

    /** The records of one thing, ordered by name. */
    fun observeThing(thing: String): Flow<List<ArchiveRecord>>

    /**
     * True while some record was never indexed, for the repair watcher. It may repeat a value after
     * any change of the index, so a watcher whose pass stopped early is asked again. The default reads
     * every record. An adapter with a cheaper count or `EXISTS` overrides it.
     */
    fun observeNeverIndexed(): Flow<Boolean> = observeAll().map { rows -> rows.any { it.awaitsIndex } }

    /**
     * [latestTimedEnds] of every record, which History uses to pick the thing it opens on. The
     * default reads every record. An adapter with a grouped query overrides it.
     */
    fun observeLatestTimed(): Flow<Map<String, Long?>> = observeAll().map(::latestTimedEnds)

    suspend fun get(key: ArchiveKey): ArchiveRecord?

    /** Inserts or replaces the record of (thing, name). */
    suspend fun put(record: ArchiveRecord)

    /**
     * Inserts [record] only when (thing, name) has none, and returns whether it did. A writer that
     * must never replace another's record (the janitor adopting an orphan) uses it. The default reads
     * then writes. An adapter shared across processes or threads overrides it to do both atomically.
     */
    suspend fun putIfAbsent(record: ArchiveRecord): Boolean {
        if (get(record.key) != null) return false
        put(record)
        return true
    }

    /**
     * Replaces the record of [key] with [transform] of its latest value, when there is one. A writer
     * that owns only some columns (index repair) uses it so it never writes back a stale snapshot over
     * another writer's change. The default reads then writes and is not atomic, so another writer can
     * land between the two. Every real adapter must override it to do both atomically (Room
     * `@Transaction`, the iOS index lock).
     */
    suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
        get(key)?.let { put(transform(it)) }
    }

    suspend fun remove(key: ArchiveKey)

    /** Records never indexed, then those whose last attempt failed, by name. */
    suspend fun unindexed(limit: Int): List<ArchiveRecord>

    /** Records with [ArchiveRecord.onTimeline] in `[fromNs, toNs)`, in [ArchiveRecord.TIMELINE_ORDER]. */
    suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord>

    /** Indexed records of one thing, for the time-estimate pass. */
    suspend fun indexedForThing(thing: String): List<ArchiveRecord>

    suspend fun things(): List<String>

    companion object {
        /**
         * Per thing, the latest real or estimated end among its indexed telemetry files that have a
         * time ([ArchiveRecord.hasHistoryTime]), as SQL `MAX` gives it. A thing without such a file is
         * absent. A thing whose timed files have no end maps to null, never to a sentinel, so a real
         * end at `Long.MIN_VALUE` stays a real end.
         */
        fun latestTimedEnds(records: Iterable<ArchiveRecord>): Map<String, Long?> {
            val latest = LinkedHashMap<String, Long?>()
            for (r in records) {
                if (!r.hasHistoryTime) continue
                val end = r.timelineLastNs
                val seen = latest[r.thing]
                latest[r.thing] = when {
                    seen == null -> end
                    end == null -> seen
                    else -> maxOf(seen, end)
                }
            }
            return latest
        }
    }
}
