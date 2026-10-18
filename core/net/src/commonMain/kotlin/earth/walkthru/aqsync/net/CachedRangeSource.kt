package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.ByteView
import earth.walkthru.aqsync.data.history.parquet.ParquetBoundsException
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import earth.walkthru.aqsync.data.history.parquet.SourceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * A [RangeSource] that serves [upstream] through aligned blocks of [blockBytes] kept in [cache] (#94).
 * A block on disk is read with no request, missing blocks are fetched in runs of consecutive blocks,
 * one upstream range per run, and written back. A second open of the same file therefore reads the
 * network only for blocks it never read or the cache gave back.
 *
 * - The key is [uri] and [generation], a token the caller changes whenever the object may have changed
 *   (the feed passes the manifest's `as_of` and `generation`). Within one key [id] comes from a small meta file, so even
 *   the footer read needs no request. Without one it asks [upstream] and keeps the answer.
 * - An id that is not [SourceId.cacheable] (no strong ETag) bypasses the cache, every read goes
 *   upstream.
 * - Whenever upstream reports another object than the kept id (another size or version after a fetch,
 *   or a [SourceChangedException]), every block of the key is dropped and the read throws
 *   [SourceChangedException] with the kept id, so the reader starts again on the new object. Blocks of
 *   two objects never mix.
 * - A cached block of the wrong length is dropped and fetched again. Writes are atomic, so a file is
 *   either whole or gone, and the OS may purge any of them.
 */
class CachedRangeSource(
    private val upstream: RangeSource,
    private val cache: BlockCache,
    private val uri: String,
    generation: String,
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
) : RangeSource {
    init {
        require(blockBytes > 0)
    }

    private val prefix = fnv64Hex("$uri\n$generation")
    private val metaName = "$prefix.meta"
    private val idLock = Mutex()

    /** Orders block writes against [invalidate], so no write of an old object lands after the drop. */
    private val writeLock = Mutex()
    private var epoch = 0L

    @Volatile
    private var known: SourceId? = null

    override suspend fun id(): SourceId = known ?: idLock.withLock { known ?: resolve().also { known = it } }

    override suspend fun readTail(n: Int): ByteView {
        if (n <= 0) return ByteView.EMPTY
        val id = id()
        if (!id.cacheable) return upstream.readTail(n)
        val want = minOf(n.toLong(), id.size)
        if (want == 0L) return ByteView.EMPTY
        return read(id, listOf(id.size - want until id.size)).single()
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> {
        if (ranges.all { it.isEmpty() }) return ranges.map { ByteView.EMPTY }
        val id = id()
        if (!id.cacheable) return upstream.readRanges(ranges)
        for (r in ranges) {
            if (r.isEmpty()) continue
            if (r.first < 0 || r.last >= id.size) throw ParquetBoundsException("range $r outside a file of ${id.size} bytes")
            if (r.last - r.first >= Int.MAX_VALUE) throw ParquetBoundsException("range $r is longer than 2 GiB")
        }
        return read(id, ranges)
    }

    override fun close() = upstream.close()

    private suspend fun resolve(): SourceId {
        cache.get(metaName)?.let { parseMeta(it) }?.let { return it }
        val id = upstream.id()
        if (id.cacheable) cache.put(metaName, "$META_VERSION\n${id.size}\n${id.uri}\n${id.version}".encodeToByteArray())
        return id
    }

    private fun parseMeta(bytes: ByteArray): SourceId? {
        val lines = bytes.decodeToString().split('\n', limit = 4)
        if (lines.size != 4 || lines[0] != META_VERSION || lines[2] != uri) return null
        val size = lines[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return SourceId(uri, size, lines[3], cacheable = true)
    }

    private suspend fun read(id: SourceId, ranges: List<LongRange>): List<ByteView> {
        val startEpoch = writeLock.withLock { epoch }
        val needed = blocksTouched(ranges.filterNot { it.isEmpty() }) { r -> r.first / blockBytes..r.last / blockBytes }
        val blocks = HashMap<Long, ByteArray>()
        val missing = ArrayList<Long>()
        for (b in needed) {
            val bytes = cache.get(blockName(b))
            when {
                bytes == null -> missing += b
                bytes.size != blockLength(id, b) -> {
                    cache.remove(blockName(b))
                    missing += b
                }
                else -> blocks[b] = bytes
            }
        }
        if (missing.isNotEmpty()) fetch(id, missing, blocks, startEpoch)
        return ranges.map { r -> if (r.isEmpty()) ByteView.EMPTY else slice(blocks, r) }
    }

    private suspend fun fetch(id: SourceId, missing: List<Long>, blocks: MutableMap<Long, ByteArray>, startEpoch: Long) {
        val runs = runs(missing)
        val views = try {
            upstream.readRanges(runs.map { run -> run.first * blockBytes until minOf((run.last + 1) * blockBytes, id.size) })
        } catch (e: SourceChangedException) {
            invalidate()
            throw SourceChangedException(id, "$uri changed while reading")
        }
        val now = upstream.id()
        if (now.size != id.size || now.version != id.version) {
            invalidate()
            throw SourceChangedException(id, "$uri changed from ${id.size} to ${now.size} bytes")
        }
        val writes = ArrayList<Pair<String, ByteArray>>()
        for ((run, view) in runs.zip(views)) {
            for (b in run) {
                val start = view.offset + ((b - run.first) * blockBytes).toInt()
                val bytes = view.array.copyOfRange(start, start + blockLength(id, b))
                blocks[b] = bytes
                writes += blockName(b) to bytes
            }
        }
        writeLock.withLock { if (epoch == startEpoch) cache.putAll(writes) }
    }

    /** Drops every block and the meta of this key, and forgets the id. */
    private suspend fun invalidate() {
        writeLock.withLock {
            epoch++
            cache.removePrefix(prefix)
        }
        idLock.withLock { known = null }
    }

    private fun slice(blocks: Map<Long, ByteArray>, r: LongRange): ByteView {
        val first = r.first / blockBytes
        val last = r.last / blockBytes
        val length = (r.last - r.first + 1).toInt()
        if (first == last) return ByteView(blocks.getValue(first), (r.first - first * blockBytes).toInt(), length)
        val out = ByteArray(length)
        var at = 0
        for (b in first..last) {
            val block = blocks.getValue(b)
            val from = if (b == first) (r.first - first * blockBytes).toInt() else 0
            val to = if (b == last) (r.last - last * blockBytes).toInt() + 1 else block.size
            block.copyInto(out, at, from, to)
            at += to - from
        }
        return ByteView(out)
    }

    private fun blockName(index: Long) = "$prefix-$index"

    private fun blockLength(id: SourceId, index: Long): Int = minOf(blockBytes.toLong(), id.size - index * blockBytes).toInt()

    companion object {
        /** 64 KiB, so a footer and a page index fit in one or two, and a file of a few MiB is a few dozen files. */
        const val DEFAULT_BLOCK_BYTES: Int = 64 * 1024

        private const val META_VERSION = "aqblocks1"

        /** Every block index any range touches, ascending. */
        private fun blocksTouched(ranges: List<LongRange>, blocksOf: (LongRange) -> LongRange): List<Long> {
            val out = HashSet<Long>()
            for (r in ranges) for (b in blocksOf(r)) out += b
            return out.sorted()
        }

        /** Ascending [indices] as runs of consecutive values. */
        private fun runs(indices: List<Long>): List<LongRange> {
            val out = ArrayList<LongRange>()
            var start = indices.first()
            var end = start
            for (i in indices.drop(1)) {
                if (i == end + 1) {
                    end = i
                } else {
                    out += start..end
                    start = i
                    end = i
                }
            }
            out += start..end
            return out
        }

        /** FNV-1a 64 of [text], 16 hex digits, a file name safe on every platform. */
        private fun fnv64Hex(text: String): String {
            var h = 0xCBF29CE484222325uL
            for (b in text.encodeToByteArray()) h = (h xor (b.toULong() and 0xFFuL)) * 0x100000001B3uL
            return h.toString(16).padStart(16, '0')
        }
    }
}
