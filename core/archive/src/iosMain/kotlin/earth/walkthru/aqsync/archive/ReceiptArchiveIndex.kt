package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val rows = MutableStateFlow<List<ArchiveRecord>?>(null)

    private suspend fun loaded(): List<ArchiveRecord> = rows.value ?: mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): List<ArchiveRecord> = rows.value ?: withContext(io) { load() }.also { rows.value = it }

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

    override fun observeAll(): Flow<List<ArchiveRecord>> = flow {
        loaded()
        emitAll(rows.filterNotNull().map { list -> list.sortedBy { it.name } })
    }

    override fun observeStation(station: String): Flow<List<ArchiveRecord>> = observeAll().map { list -> list.filter { it.station == station } }

    override suspend fun get(key: ArchiveKey): ArchiveRecord? = loaded().firstOrNull { it.station == key.station && it.name == key.name }

    override suspend fun put(record: ArchiveRecord) {
        mutex.withLock { putLocked(loadLocked(), record) }
    }

    /** Reads the latest record and writes its replacement under one lock, so no concurrent put is lost. */
    override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) {
        mutex.withLock {
            val current = loadLocked()
            val latest = current.firstOrNull { it.station == key.station && it.name == key.name } ?: return@withLock
            putLocked(current, transform(latest))
        }
    }

    private suspend fun putLocked(current: List<ArchiveRecord>, record: ArchiveRecord) {
        withContext(io) {
            val path = receiptPath(record.key)
            val kind = IosFiles.kind(path)
            check(kind == Kind.ABSENT || kind == Kind.FILE) { "archive receipt is not a file" }
            IosFiles.mkdirs(path.substringBeforeLast('/'))
            atomic.writeAtomically(path, encode(record).encodeToByteArray(), StorageClass.Durable)
        }
        rows.value = current.filterNot { it.station == record.station && it.name == record.name } + record
    }

    /** Forgets the receipt only. The verified copy stays in the archive. */
    override suspend fun remove(key: ArchiveKey) {
        mutex.withLock {
            val current = loadLocked()
            withContext(io) {
                val path = receiptPath(key)
                if (IosFiles.kind(path) == Kind.FILE) IosFiles.remove(path)
            }
            rows.value = current.filterNot { it.station == key.station && it.name == key.name }
        }
    }

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = loaded()
        .filter { it.indexedAtEpochMs == null || it.indexError != null }
        .sortedWith(compareBy<ArchiveRecord> { it.indexedAtEpochMs != null }.thenBy { it.name })
        .take(limit)

    override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
        loaded().filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)

    override suspend fun indexedForStation(station: String): List<ArchiveRecord> = loaded().filter { it.station == station && it.indexed }

    override suspend fun stations(): List<String> = loaded().map { it.station }.distinct()

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
        }.toString()

        /** Null for anything without a valid `size` and `crc`, which then reads as not synced. */
        fun decode(station: String, name: String, text: String): ArchiveRecord? {
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun long(key: String): Long? = o[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
            fun int(key: String): Int? = o[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            fun text(key: String): String? = o[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val size = long("size")?.takeIf { it >= 0 } ?: return null
            val crc = long("crc")?.takeIf { it in 0..0xFFFF_FFFFL } ?: return null
            return ArchiveRecord(
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
        }
    }
}
