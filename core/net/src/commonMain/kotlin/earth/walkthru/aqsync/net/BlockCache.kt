package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One file a [BlockStore] holds. */
class StoredBlock(val name: String, val bytes: Long, val modifiedEpochMs: Long)

/** Flat named files for a [BlockCache]. Every call blocks on disk I/O. */
interface BlockStore {
    /** The bytes of [name], null when it is not there. */
    fun read(name: String): ByteArray?

    fun write(name: String, bytes: ByteArray)

    /** Removes [name]. No error when it is already gone. */
    fun delete(name: String)

    fun list(): List<StoredBlock>
}

/**
 * A [BlockStore] in one directory of the OS cache. Writes go through [atomic] as
 * [StorageClass.Rebuildable], the OS may purge the directory at any time, and a file that is gone reads
 * as a miss.
 */
class DiskBlockStore(
    private val dir: String,
    private val atomic: AtomicFiles,
    private val tree: StorageTree,
) : BlockStore {
    init {
        require(dir.startsWith("/") && !dir.endsWith("/")) { "block cache directory must be absolute without a trailing slash" }
    }

    override fun read(name: String): ByteArray? = readFileOrNull("$dir/$name")

    override fun write(name: String, bytes: ByteArray) = atomic.writeAtomically("$dir/$name", bytes, StorageClass.Rebuildable)

    override fun delete(name: String) = tree.delete("$dir/$name")

    override fun list(): List<StoredBlock> = tree.list(dir).filter { !it.isDirectory && '/' !in it.path }.map { StoredBlock(it.path, it.bytes, it.modifiedEpochMs) }
}

/** The bytes of the file at [path], null when there is none. Blocks on disk I/O. */
internal expect fun readFileOrNull(path: String): ByteArray?

/** [BlockStore] in memory, for tests and previews. */
class MemoryBlockStore : BlockStore {
    private val files = LinkedHashMap<String, ByteArray>()
    private var tick = 0L
    private val modified = HashMap<String, Long>()

    override fun read(name: String): ByteArray? = files[name]?.copyOf()

    override fun write(name: String, bytes: ByteArray) {
        files[name] = bytes.copyOf()
        modified[name] = ++tick
    }

    override fun delete(name: String) {
        files.remove(name)
        modified.remove(name)
    }

    override fun list(): List<StoredBlock> = files.map { (name, bytes) -> StoredBlock(name, bytes.size.toLong(), modified.getValue(name)) }
}

/**
 * A bounded byte cache on disk, least recently used first out, inside the rebuildable cache quota
 * (#94). [CachedRangeSource] keeps the blocks of remote files in it.
 *
 * The recency order is rebuilt from file times when the cache is first used, and follows reads and
 * writes from then on. [quotaBytes] is read at every [put] and [enforce], and each platform computes
 * it from the OS cache allowance, so a shrinking allowance under storage pressure shrinks the cache
 * and a quota of 0 empties it. A [put] makes room before each write, so the files never pass the
 * quota. The apps call [enforce] once at start. [clear] removes every file, the Settings clear action. Writes are
 * best effort. A failed write or delete is counted, never thrown, since the cache only saves a GET.
 * Disk work runs on [io]. Nothing is logged.
 */
class BlockCache(
    private val store: BlockStore,
    private val quotaBytes: suspend () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Counters since start, for tests and diagnostics. */
    data class Stats(val hits: Int = 0, val misses: Int = 0, val writes: Int = 0, val evicted: Int = 0, val failures: Int = 0)

    private val lock = Mutex()

    /** Name to size, least recently used first. Null until the first call lists the store. */
    private var index: LinkedHashMap<String, Long>? = null
    private var total = 0L
    private var stats = Stats()

    suspend fun stats(): Stats = lock.withLock { stats }

    /** Bytes the cache holds now. */
    suspend fun bytes(): Long = locked { total }

    /** The bytes of [name], or null on a miss. */
    suspend fun get(name: String): ByteArray? = locked { files ->
        val bytes = if (files.containsKey(name)) attempt { store.read(name) } else null
        if (bytes == null) {
            // A file that failed to read is deleted too. One the OS purged is already gone.
            if (!files.containsKey(name) || attempt { store.delete(name) } != null) forget(files, name)
            stats = stats.copy(misses = stats.misses + 1)
        } else {
            files.remove(name)
            files[name] = bytes.size.toLong()
            stats = stats.copy(hits = stats.hits + 1)
        }
        bytes
    }

    /** Stores [bytes] as [name], then evicts down to the quota. */
    suspend fun put(name: String, bytes: ByteArray) = putAll(listOf(name to bytes))

    /**
     * Stores every entry, evicting before each write so the cache stays under the quota, asked once.
     * Nothing is written while the quota is 0 or cannot be read, and an entry larger than the quota is
     * skipped.
     */
    suspend fun putAll(entries: List<Pair<String, ByteArray>>) {
        val quota = quota() ?: return
        locked { files ->
            for ((name, bytes) in entries) {
                val size = bytes.size.toLong()
                if (quota == 0L || size > quota) continue
                val old = files[name] ?: 0L
                evictTo(files, quota - size + old, keep = name)
                if (total - old + size > quota) continue
                if (attempt { store.write(name, bytes) } == null) continue
                forget(files, name)
                files[name] = size
                total += size
                stats = stats.copy(writes = stats.writes + 1)
            }
            evictTo(files, quota)
        }
    }

    /** Removes [name]. */
    suspend fun remove(name: String) = locked { files ->
        if (attempt { store.delete(name) } != null) forget(files, name)
    }

    /** Removes every file whose name starts with [prefix]. */
    suspend fun removePrefix(prefix: String) = locked { files ->
        for (name in files.keys.filter { it.startsWith(prefix) }) {
            if (attempt { store.delete(name) } != null) forget(files, name)
        }
    }

    /** Evicts down to the quota without writing, for a quota that shrank. */
    suspend fun enforce() {
        val quota = quota() ?: return
        locked { evictTo(it, quota) }
    }

    /** Removes every file. */
    suspend fun clear() = locked { evictTo(it, 0L) }

    /** Deletes the least recently used files but [keep] until [total] is at most [quota]. */
    private fun evictTo(files: LinkedHashMap<String, Long>, quota: Long, keep: String? = null) {
        val iterator = files.entries.iterator()
        while (total > quota && iterator.hasNext()) {
            val (name, size) = iterator.next()
            if (name == keep) continue
            if (attempt { store.delete(name) } == null) continue
            iterator.remove()
            total -= size
            stats = stats.copy(evicted = stats.evicted + 1)
        }
    }

    private fun forget(files: LinkedHashMap<String, Long>, name: String) {
        files.remove(name)?.let { total -= it }
    }

    /** The quota now, or null when the platform cannot tell. */
    private suspend fun quota(): Long? = try {
        quotaBytes()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [block] or null when it throws, counting the failure. */
    private inline fun <T : Any> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        stats = stats.copy(failures = stats.failures + 1)
        null
    }

    private suspend fun <T> locked(block: (LinkedHashMap<String, Long>) -> T): T = withContext(io) {
        lock.withLock {
            val files = index ?: LinkedHashMap<String, Long>().also { fresh ->
                val listed = attempt { store.list() }.orEmpty()
                for (b in listed.sortedWith(compareBy({ it.modifiedEpochMs }, { it.name }))) fresh[b.name] = b.bytes
                total = listed.sumOf { it.bytes }
                index = fresh
            }
            block(files)
        }
    }
}
