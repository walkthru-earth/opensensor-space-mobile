package space.opensensor.data.history.parquet

/**
 * A read-only window `[offset, offset + length)` over [array]. The reader slices and decodes views and
 * never copies them, so whoever made a view must not change the bytes under it while it is in use.
 */
class ByteView(val array: ByteArray, val offset: Int = 0, val length: Int = array.size - offset) {
    init {
        if (offset < 0 || length < 0 || offset.toLong() + length > array.size) {
            throw ParquetBoundsException("view [$offset, ${offset.toLong() + length}) outside ${array.size} bytes")
        }
    }

    operator fun get(i: Int): Byte {
        if (i < 0 || i >= length) throw ParquetBoundsException("index $i outside a view of $length bytes")
        return array[offset + i]
    }

    /** `[from, from + length)` of this view, sharing the array. */
    fun slice(from: Int, length: Int): ByteView {
        if (from < 0 || length < 0 || from.toLong() + length > this.length) {
            throw ParquetBoundsException("slice [$from, ${from.toLong() + length}) outside a view of ${this.length} bytes")
        }
        return ByteView(array, offset + from, length)
    }

    /** A copy the caller owns. Only for callers that keep bytes past the source (tests, caches). */
    fun toByteArray(): ByteArray = array.copyOfRange(offset, offset + length)

    internal fun cursor(): ByteCursor = ByteCursor(array, offset, offset + length)

    companion object {
        val EMPTY: ByteView = ByteView(ByteArray(0))
    }
}

/**
 * The identity of one immutable version of a file. [version] is the ETag over HTTP and size plus
 * modification time for a local file, so a replaced object never matches a cached footer. [cacheable]
 * is false when [version] cannot tell two versions of the same size apart (an HTTP object with a weak
 * or missing ETag); a [FooterCache] then keeps nothing for it.
 */
data class SourceId(val uri: String, val size: Long, val version: String, val cacheable: Boolean = true)

/**
 * Bytes of one file, many ranges per call. Ranges are byte offsets in the file, `first..last`
 * inclusive (`start until end` reads `[start, end)`); an empty range yields [ByteView.EMPTY].
 * [readRanges] returns one view per input range, in input order, whatever order the source fetched
 * them in. A raw source does one read per range; [CoalescingRangeSource] plans and merges them.
 *
 * Implementations: [MemoryRangeSource], [LocalRangeSource] and, in `:core:net`, the HTTP source.
 */
interface RangeSource : AutoCloseable {
    /** Identity of the bytes this source serves. An HTTP source learns it from its first response. */
    suspend fun id(): SourceId

    /** The last `min(n, size)` bytes of the file, one request (an HTTP suffix range `bytes=-n`). */
    suspend fun readTail(n: Int): ByteView

    /** One view per range, in input order. Throws [IndexOutOfBoundsException] for a range outside the file. */
    suspend fun readRanges(ranges: List<LongRange>): List<ByteView>

    override fun close() {}
}

/**
 * How a [CoalescingRangeSource] turns many small ranges into few requests, and how the reader opens a
 * file. Ranges closer than [coalesceGapBytes] merge into one request (the gap bytes are read and
 * dropped), a merged request stays at or under [maxRequestBytes], at most [parallelism] requests run at
 * once, a file under [wholeFileBelowBytes] is read whole, and the first read of a file is a tail of
 * [tailPrefetchBytes], which holds the footer and page index of every file the app reads.
 */
data class RangePolicy(
    val coalesceGapBytes: Long,
    val maxRequestBytes: Long,
    val parallelism: Int,
    val wholeFileBelowBytes: Long,
    val tailPrefetchBytes: Int,
) {
    init {
        require(coalesceGapBytes >= 0 && maxRequestBytes > 0 && parallelism > 0 && wholeFileBelowBytes >= 0 && tailPrefetchBytes >= 8)
    }

    companion object {
        /** A local file or memory: a seek costs little, so merge only near neighbours, one at a time. */
        val LOCAL = RangePolicy(4 * KIB, 8 * MIB, parallelism = 1, wholeFileBelowBytes = 256 * KIB, tailPrefetchBytes = 64 * 1024)

        /** Metered mobile data: a round trip costs far more than a few hundred KiB of gap. */
        val CELLULAR = RangePolicy(256 * KIB, 8 * MIB, parallelism = 4, wholeFileBelowBytes = 512 * KIB, tailPrefetchBytes = 128 * 1024)

        /** Unmetered Wi-Fi: the arrow-rs object store defaults (1 MiB gap, parallelism under its cap of 10). */
        val WIFI = RangePolicy(1 * MIB, 16 * MIB, parallelism = 6, wholeFileBelowBytes = 1 * MIB, tailPrefetchBytes = 256 * 1024)

        private const val KIB = 1024L
        private const val MIB = 1024L * 1024L
    }
}

/** Bytes already in memory. Every view shares [bytes], so the reader decodes them in place. */
class MemoryRangeSource(
    private val bytes: ByteArray,
    private val uri: String = "memory",
    /** Keep it unique per content when a [FooterCache] sees several arrays under one [uri]. */
    private val version: String = "",
) : RangeSource {
    override suspend fun id(): SourceId = SourceId(uri, bytes.size.toLong(), version)

    override suspend fun readTail(n: Int): ByteView {
        val length = minOf(n.coerceAtLeast(0), bytes.size)
        return ByteView(bytes, bytes.size - length, length)
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> = ranges.map { view(it) }

    private fun view(range: LongRange): ByteView {
        if (range.isEmpty()) return ByteView.EMPTY
        val length = rangeLength(range)
        checkRange(range.first, length, bytes.size.toLong())
        return ByteView(bytes, range.first.toInt(), length)
    }
}

/** Length of a non-empty [range] as an Int; a read longer than 2 GiB is refused. */
internal fun rangeLength(range: LongRange): Int {
    if (range.isEmpty()) return 0
    if (range.first < 0) throw ParquetBoundsException("range $range starts before the file")
    // With a non-negative start the difference cannot overflow, even for a range ending at Long.MAX_VALUE.
    if (range.last - range.first >= Int.MAX_VALUE) throw ParquetBoundsException("range $range is longer than 2 GiB")
    return (range.last - range.first + 1).toInt()
}

/** `[offset, offset + length)` as an inclusive [LongRange]. */
fun byteRange(offset: Long, length: Int): LongRange = offset until offset + length

/**
 * The object behind a [RangeSource] changed between reads (an HTTP `If-Range` answered 200 with a new
 * ETag). Bytes read under [previous] must not be combined with later ones. The reader drops cached
 * footers for [previous] and starts the read again once. A second change fails as a [ParquetFormatException].
 */
class SourceChangedException(val previous: SourceId, message: String) : RuntimeException(message)
