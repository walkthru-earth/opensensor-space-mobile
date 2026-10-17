package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The iOS [ArchiveIndex] until Room KMP lands: one receipt per verified file, beside it as
 * `<hive name>.verified.json` ([ArchiveLayout.LEGACY_RECEIPT_SUFFIX]), loaded once into memory.
 *
 * A receipt keeps the older build's `size` and `crc` keys, so a receipt written here still reads
 * there, and adds the rest of the [ArchiveRecord]. An older receipt with only `size` and `crc` reads
 * as a record with an empty `sha256`, which the engine fills the next time it checks that copy. A
 * receipt counts only while its final file is a regular file.
 */
class ReceiptArchiveIndex(
    private val finalRoot: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Receipts are Durable: flushed with `F_FULLFSYNC` and renamed into place (#60). */
    private val atomic: AtomicFiles = IosAtomicFiles(IosArchiveStore.tempDirBeside(finalRoot)),
) : ArchiveIndex {
    private val mutex = Mutex()

    /** Authoritative keyed records, read and changed only under [mutex]. */
    private val records = LinkedHashMap<ArchiveKey, ArchiveRecord>()

    /** Replaced only under [mutex]. Readers take the current value without the lock. */
    private val rows = MutableStateFlow<Snapshot?>(null)

    private suspend fun loaded(): Snapshot = rows.value ?: mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): Snapshot = rows.value ?: withContext(io) { load() }.let { loaded ->
        loaded.forEach { records[it.key] = it }
        Snapshot.of(records.values.toList()).also { rows.value = it }
    }

    private fun load(): List<ArchiveRecord> = IosFiles.walk(finalRoot).mapNotNull { relative ->
        if (!relative.endsWith(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)) return@mapNotNull null
        val station = relative.substringBefore('/', missingDelimiterValue = "")
        val name = relative.substringAfter('/').removeSuffix(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)
        if (!ArchiveLayout.isSafeStation(station) || !ArchiveLayout.isArchiveName(name)) return@mapNotNull null
        if (IosFiles.kind("$finalRoot/$station/$name") != Kind.FILE) return@mapNotNull null
        val bytes = IosFiles.readSmall("$finalRoot/$relative", RECEIPT_MAX_BYTES) ?: return@mapNotNull null
        decode(station, name, bytes.decodeToString())
    }

    private fun receiptPath(key: ArchiveKey): String =
        "$finalRoot/${key.station}/${key.name}${ArchiveLayout.LEGACY_RECEIPT_SUFFIX}".also { IosFiles.checkNoLinks(it, finalRoot) }

    private fun <T> observe(select: (Snapshot) -> T): Flow<T> = flow {
        loaded()
        emitAll(rows.filterNotNull().map(select))
    }

    /** Already sorted, so an emission costs no sort. */
    override fun observeAll(): Flow<List<ArchiveRecord>> = observe { it.all }

    override fun observeStation(station: String): Flow<List<ArchiveRecord>> = observe { snapshot -> snapshot.all.filter { it.station == station } }

    /** From a count kept with each snapshot, so no record is read. */
    override fun observeNeverIndexed(): Flow<Boolean> = observe { it.neverIndexed > 0 }

    override fun observeLatestTimed(): Flow<Map<String, Long>> = observe { it.latestTimed }.distinctUntilChanged()

    /** Expected O(1) keyed lookup. The lock prevents observing an unfinished durable receipt write. */
    override suspend fun get(key: ArchiveKey): ArchiveRecord? = mutex.withLock {
        loadLocked()
        records[key]
    }

    override suspend fun put(record: ArchiveRecord) {
        mutex.withLock { putLocked(loadLocked(), record) }
    }

    /** Writes the receipt with [AtomicFiles.createNoReplace] under the lock, so an existing receipt always wins. */
    override suspend fun putIfAbsent(record: ArchiveRecord): Boolean = mutex.withLock {
        val current = loadLocked()
        if (records.containsKey(record.key)) return@withLock false
        val written = withContext(io) {
            try {
                atomic.createNoReplace(receiptPath(record.key), encode(record).encodeToByteArray(), StorageClass.Durable)
                true
            } catch (_: AlreadyCommitted) {
                false
            }
        }
        if (written) {
            records[record.key] = record
            rows.value = current.with(record)
        }
        written
    }

    /** Reads the latest record and writes its replacement under one lock, so no concurrent put is lost. */
    override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
        mutex.withLock {
            val current = loadLocked()
            val latest = records[key] ?: return@withLock
            putLocked(current, transform(latest))
        }
    }

    private suspend fun putLocked(current: Snapshot, record: ArchiveRecord) {
        withContext(io) {
            val path = receiptPath(record.key)
            val kind = IosFiles.kind(path)
            check(kind == Kind.ABSENT || kind == Kind.FILE) { "archive receipt is not a file" }
            // AtomicFiles creates any new directory and syncs it into its parent.
            atomic.writeAtomically(path, encode(record).encodeToByteArray(), StorageClass.Durable)
        }
        records[record.key] = record
        rows.value = current.with(record)
    }

    /** Forgets the receipt only. The verified copy stays in the archive. */
    override suspend fun remove(key: ArchiveKey) {
        mutex.withLock {
            val current = loadLocked()
            withContext(io) {
                val path = receiptPath(key)
                if (IosFiles.kind(path) == Kind.FILE) IosFiles.remove(path)
            }
            records.remove(key)
            rows.value = current.without(key)
        }
    }

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = loaded().all
        .filter { !it.isIndexed }
        .sortedWith(compareBy<ArchiveRecord> { !it.awaitsIndex }.thenBy { it.name })
        .take(limit)

    override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
        loaded().all.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)

    override suspend fun indexedForStation(station: String): List<ArchiveRecord> = loaded().all.filter { it.station == station && it.isIndexed }

    override suspend fun stations(): List<String> = loaded().all.map { it.station }.distinct()

    /**
     * One immutable view of the receipts, sorted by name, then station, and never changed once
     * published, so flow readers need no lock. Keyed API reads use [records]. Publishing a changed
     * sorted snapshot needs one binary search and an O(N) array copy, without sorting every emission.
     * That publication cost is separate from the expected O(1) keyed map mutation.
     */
    internal class Snapshot private constructor(private val sorted: Array<ArchiveRecord>, val neverIndexed: Int) {
        val all: List<ArchiveRecord> = sorted.asList()

        /** Computed once per snapshot, and only when observed. */
        val latestTimed: Map<String, Long> by lazy { ArchiveIndex.latestTimedEnds(all) }

        operator fun get(key: ArchiveKey): ArchiveRecord? = indexOf(key.name, key.station).let { if (it >= 0) sorted[it] else null }

        /** [record] inserted at its place, or replacing the record of its key. */
        fun with(record: ArchiveRecord): Snapshot {
            val at = indexOf(record.name, record.station)
            val gained = pending(record)
            if (at >= 0) {
                val next = sorted.copyOf()
                next[at] = record
                return Snapshot(next, neverIndexed - pending(sorted[at]) + gained)
            }
            val insert = -at - 1
            val next = arrayOfNulls<ArchiveRecord>(sorted.size + 1)
            sorted.copyInto(next, 0, 0, insert)
            next[insert] = record
            sorted.copyInto(next, insert + 1, insert)
            @Suppress("UNCHECKED_CAST")
            return Snapshot(next as Array<ArchiveRecord>, neverIndexed + gained)
        }

        fun without(key: ArchiveKey): Snapshot {
            val at = indexOf(key.name, key.station)
            if (at < 0) return this
            val next = arrayOfNulls<ArchiveRecord>(sorted.size - 1)
            sorted.copyInto(next, 0, 0, at)
            sorted.copyInto(next, at, at + 1)
            @Suppress("UNCHECKED_CAST")
            return Snapshot(next as Array<ArchiveRecord>, neverIndexed - pending(sorted[at]))
        }

        /** The index of (name, station), or `-(insertion point) - 1` when absent, as `binarySearch` returns. */
        private fun indexOf(name: String, station: String): Int {
            var low = 0
            var high = sorted.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val r = sorted[mid]
                val c = r.name.compareTo(name).takeIf { it != 0 } ?: r.station.compareTo(station)
                when {
                    c < 0 -> low = mid + 1
                    c > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -(low + 1)
        }

        companion object {
            private val ORDER: Comparator<ArchiveRecord> = compareBy<ArchiveRecord> { it.name }.thenBy { it.station }

            private fun pending(r: ArchiveRecord): Int = if (r.awaitsIndex) 1 else 0

            /** One sort at load. [records] has one record per key, as one receipt per file gives. */
            fun of(records: List<ArchiveRecord>): Snapshot {
                val sorted = records.sortedWith(ORDER).toTypedArray()
                return Snapshot(sorted, sorted.count { it.awaitsIndex })
            }
        }
    }

    internal companion object {
        private const val RECEIPT_MAX_BYTES = 16_384L

        fun encode(r: ArchiveRecord): String = buildJsonObject {
            put("size", r.bytes)
            put("crc", r.crc32)
            put("sha256", r.sha256)
            put("syncedAtEpochMs", r.syncedAtEpochMs)
            put("sourceDevice", r.sourceDevice)
            r.indexedAtEpochMs?.let { put("indexedAtEpochMs", it) }
            r.indexError?.let { put("indexError", it) }
            r.rows?.let { put("rows", it) }
            r.rowGroups?.let { put("rowGroups", it) }
            r.schemaVersion?.let { put("schemaVersion", it) }
            r.bootId?.let { put("bootId", it) }
            r.purpose?.let { put("purpose", it) }
            r.utcRows?.let { put("utcRows", it) }
            r.firstUtcNs?.let { put("firstUtcNs", it) }
            r.lastUtcNs?.let { put("lastUtcNs", it) }
            r.firstMonoUs?.let { put("firstMonoUs", it) }
            r.lastMonoUs?.let { put("lastMonoUs", it) }
            r.firstSeq?.let { put("firstSeq", it) }
            r.lastSeq?.let { put("lastSeq", it) }
            r.pmValidRows?.let { put("pmValidRows", it) }
            r.estFirstUtcNs?.let { put("estFirstUtcNs", it) }
            r.estLastUtcNs?.let { put("estLastUtcNs", it) }
            r.estAnchorFile?.let { put("estAnchorFile", it) }
            put("indexLayout", ArchiveRecord.INDEX_LAYOUT)
        }.toString()

        /**
         * Null for anything without a valid `size` and `crc`, which then reads as not synced. A receipt
         * from an older index layout with no UTC rows reads as never indexed, so repair indexes it again.
         */
        fun decode(station: String, name: String, text: String): ArchiveRecord? {
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun long(key: String): Long? = o[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
            fun int(key: String): Int? = o[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            fun text(key: String): String? = o[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val size = long("size")?.takeIf { it >= 0 } ?: return null
            val crc = long("crc")?.takeIf { it in 0..0xFFFF_FFFFL } ?: return null
            val record = ArchiveRecord(
                station = station,
                name = name,
                bytes = size,
                crc32 = crc,
                sha256 = text("sha256").orEmpty(),
                syncedAtEpochMs = long("syncedAtEpochMs") ?: 0L,
                sourceDevice = text("sourceDevice").orEmpty(),
                indexedAtEpochMs = long("indexedAtEpochMs"),
                indexError = text("indexError"),
                rows = long("rows"),
                rowGroups = int("rowGroups"),
                schemaVersion = text("schemaVersion"),
                bootId = text("bootId"),
                purpose = text("purpose"),
                utcRows = long("utcRows"),
                firstUtcNs = long("firstUtcNs"),
                lastUtcNs = long("lastUtcNs"),
                firstMonoUs = long("firstMonoUs"),
                lastMonoUs = long("lastMonoUs"),
                firstSeq = long("firstSeq"),
                lastSeq = long("lastSeq"),
                pmValidRows = long("pmValidRows"),
                estFirstUtcNs = long("estFirstUtcNs"),
                estLastUtcNs = long("estLastUtcNs"),
                estAnchorFile = text("estAnchorFile"),
            )
            val stale = (int("indexLayout") ?: 1) < ArchiveRecord.INDEX_LAYOUT && record.needsUtcReindex
            return if (stale) record.copy(indexedAtEpochMs = null) else record
        }
    }
}
