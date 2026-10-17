package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.storage.AlreadyCommitted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An [ArchiveStore] over maps keyed by [ArchiveLayout] paths. A copy of `:core:archive`'s test double. */
class MemoryArchiveStore : ArchiveStore {
    /** Path relative to the root (as [ArchiveLayout] builds it) to bytes. */
    val files = LinkedHashMap<String, ByteArray>()

    /** A storage fault to raise on a write to that file, as a full disk would. */
    var failWrite: (ArchiveKey) -> Exception? = { null }

    /** A storage fault to raise when that file is moved into the archive. */
    var failCommit: (ArchiveKey) -> Exception? = { null }

    /** Runs just before a commit, so a test can place a final the planner did not see. */
    var beforeCommit: (ArchiveKey) -> Unit = {}

    /** Partial writers opened and not yet closed. */
    var openWriters: Int = 0
        private set

    override suspend fun partialLengths(station: String): Map<String, Long> {
        val dir = ArchiveLayout.partialStationDir(station) + "/"
        return files.filterKeys { it.startsWith(dir) }.mapNotNull { (path, bytes) ->
            ArchiveLayout.nameOfPartial(path.removePrefix(dir))?.let { it to bytes.size.toLong() }
        }.toMap()
    }

    override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter {
        val path = ArchiveLayout.partialPath(key)
        val metaPath = ArchiveLayout.partialMetaPath(key)
        val stored = files[metaPath]?.decodeToString()?.let(ArchiveLayout::decodeMeta)
        val existing = files[path]
        val resumeFrom = if (existing != null && stored == meta && existing.size <= meta.size) existing.size.toLong() else 0L
        if (resumeFrom == 0L) files[path] = ByteArray(0)
        files[metaPath] = ArchiveLayout.encodeMeta(meta).encodeToByteArray()
        openWriters++
        return object : PartialWriter {
            private var closed = false
            override val resumeFrom: Long = resumeFrom
            override fun writeAt(offset: Long, payload: ByteArray) {
                failWrite(key)?.let { throw it }
                val old = files.getValue(path)
                val end = maxOf(old.size, offset.toInt() + payload.size)
                val grown = old.copyOf(end)
                payload.copyInto(grown, offset.toInt())
                files[path] = grown
            }
            override fun length(): Long = files.getValue(path).size.toLong()
            override fun sync() = Unit
            override fun close() {
                if (!closed) openWriters--
                closed = true
            }
        }
    }

    override suspend fun discardPartial(key: ArchiveKey) {
        files.remove(ArchiveLayout.partialPath(key))
        files.remove(ArchiveLayout.partialMetaPath(key))
    }

    override suspend fun exists(key: ArchiveKey): Boolean = ArchiveLayout.finalPath(key) in files

    override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
        val path = if (partial) ArchiveLayout.partialPath(key) else ArchiveLayout.finalPath(key)
        return ByteArrayReader(files[path] ?: throw NoSuchElementException(path))
    }

    override suspend fun commit(key: ArchiveKey) {
        failCommit(key)?.let { throw it }
        beforeCommit(key)
        val final = ArchiveLayout.finalPath(key)
        if (final in files) throw AlreadyCommitted()
        files[final] = files.remove(ArchiveLayout.partialPath(key)) ?: error("no partial to commit")
        files.remove(ArchiveLayout.partialMetaPath(key))
    }

    override suspend fun listFinal(station: String?): List<ArchiveKey> = files.keys.mapNotNull { path ->
        val parts = path.split('/', limit = 3)
        if (parts.size != 3 || parts[0] != ArchiveLayout.ARCHIVE) return@mapNotNull null
        if (station != null && parts[1] != station) return@mapNotNull null
        if (!ArchiveLayout.isSafeStation(parts[1]) || !ArchiveLayout.isArchiveName(parts[2])) null else ArchiveKey(parts[1], parts[2])
    }
}

/** An [ArchiveIndex] over a list, with the same ordering and predicates the Room adapter has. */
class MemoryArchiveIndex : ArchiveIndex {
    private val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())

    override fun observeAll(): Flow<List<ArchiveRecord>> = rows.map { list -> list.sortedBy { it.name } }

    override fun observeStation(station: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.station == station }.sortedBy { it.name } }

    override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.station == key.station && it.name == key.name }

    /** Writes so far, to prove one record per verified file. */
    var puts: Int = 0
        private set

    val records: List<ArchiveRecord> get() = rows.value

    override suspend fun put(record: ArchiveRecord) {
        puts++
        rows.value = rows.value.filterNot { it.station == record.station && it.name == record.name } + record
    }

    override suspend fun remove(key: ArchiveKey) {
        rows.value = rows.value.filterNot { it.station == key.station && it.name == key.name }
    }

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value
        .filter { !it.isIndexed }
        .sortedWith(compareBy<ArchiveRecord> { !it.awaitsIndex }.thenBy { it.name })
        .take(limit)

    override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
        rows.value.filter { it.station == station && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)

    override suspend fun indexedForStation(station: String): List<ArchiveRecord> = rows.value.filter { it.station == station && it.isIndexed }

    override suspend fun stations(): List<String> = rows.value.map { it.station }.distinct()
}
