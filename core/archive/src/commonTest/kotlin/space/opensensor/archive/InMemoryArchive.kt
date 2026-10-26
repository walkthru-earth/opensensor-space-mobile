package space.opensensor.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.storage.AlreadyCommitted

/** An [ArchiveStore] over maps keyed by [ArchiveLayout] paths, for the contract tests and engine tests. */
class InMemoryArchiveStore : ArchiveStore {
    /** Path relative to the root (as [ArchiveLayout] builds it) to bytes. */
    val files = LinkedHashMap<String, ByteArray>()

    override suspend fun partialLengths(thing: String): Map<String, Long> {
        val dir = ArchiveLayout.partialThingDir(thing) + "/"
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
        return object : PartialWriter {
            override val resumeFrom: Long = resumeFrom
            override fun writeAt(offset: Long, payload: ByteArray) {
                val old = files.getValue(path)
                val end = maxOf(old.size, offset.toInt() + payload.size)
                val grown = old.copyOf(end)
                payload.copyInto(grown, offset.toInt())
                files[path] = grown
            }
            override fun length(): Long = files.getValue(path).size.toLong()
            override fun sync() = Unit
            override fun close() = Unit
        }
    }

    override suspend fun discardPartial(key: ArchiveKey) {
        files.remove(ArchiveLayout.partialPath(key))
        files.remove(ArchiveLayout.partialMetaPath(key))
    }

    override suspend fun exists(key: ArchiveKey): Boolean = ArchiveLayout.finalPath(key) in files

    override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
        val path = if (partial) ArchiveLayout.partialPath(key) else ArchiveLayout.finalPath(key)
        return ByteArrayReader(files[path] ?: throw ArchiveFileMissingException(path))
    }

    override suspend fun commit(key: ArchiveKey) {
        val final = ArchiveLayout.finalPath(key)
        if (final in files) throw AlreadyCommitted()
        files[final] = files.remove(ArchiveLayout.partialPath(key)) ?: error("no partial to commit")
        files.remove(ArchiveLayout.partialMetaPath(key))
    }

    override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.mapNotNull { path ->
        val parts = path.split('/', limit = 3)
        if (parts.size != 3 || parts[0] != ArchiveLayout.ARCHIVE) return@mapNotNull null
        if (thing != null && parts[1] != thing) return@mapNotNull null
        if (!ArchiveLayout.isSafeThing(parts[1]) || !ArchiveLayout.isArchiveName(parts[2])) null else ArchiveKey(parts[1], parts[2])
    }
}

/** An [ArchiveIndex] over a list, with the same ordering and predicates the Room adapter has. */
class InMemoryArchiveIndex : ArchiveIndex {
    private val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())

    override fun observeAll(): Flow<List<ArchiveRecord>> = rows.map { list -> list.sortedBy { it.name } }

    override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.thing == thing }.sortedBy { it.name } }

    override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.thing == key.thing && it.name == key.name }

    override suspend fun put(record: ArchiveRecord) {
        rows.value = rows.value.filterNot { it.thing == record.thing && it.name == record.name } + record
    }

    override suspend fun remove(key: ArchiveKey) {
        rows.value = rows.value.filterNot { it.thing == key.thing && it.name == key.name }
    }

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value
        .filter { !it.isIndexed }
        .sortedWith(compareBy<ArchiveRecord> { !it.awaitsIndex }.thenBy { it.name })
        .take(limit)

    override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
        rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)

    override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }

    override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
}
