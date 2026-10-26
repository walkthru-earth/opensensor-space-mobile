package space.opensensor.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.opensensor.data.history.parquet.ByteView
import space.opensensor.data.history.parquet.ParquetBoundsException
import space.opensensor.data.history.parquet.RangeSource
import space.opensensor.data.history.parquet.SourceChangedException
import space.opensensor.data.history.parquet.SourceId
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * A [RangeSource] over one HTTP object, one GET per request through [engine].
 *
 * - [readTail] sends `Range: bytes=-n`. A 206 with `Content-Range: bytes a-b/total` gives the size and
 *   pins the ETag. A 200 means the server ignored the range and the body is the whole object. A 416
 *   (an empty object) falls back to one full GET.
 * - [id] reads a tail of [openTailBytes] when nothing is known yet and keeps it, so the reader's first
 *   [readTail] of up to that many bytes needs no request.
 * - [readRanges] sends one GET per non-empty range with `If-Range` set to the pinned ETag, checks that a
 *   206 covers exactly the requested bytes, and treats a 200 for a different object (new ETag or size)
 *   as a replaced object. It then pins the new object and throws [SourceChangedException] with the
 *   previous id, so the reader drops what it read and starts again.
 * - Once the whole object is in memory (a 200, or a tail that covered the file) every read is served
 *   from it with no request.
 * - No body is buffered past its [BodyLimit]: a 206 at most the requested length, any other status
 *   (a whole object) at most [maxWholeBytes]. That bounds a server that ignores `Range`, an `If-Range`
 *   mismatch and the 416 full GET alike. A larger body fails with [HttpRangeException].
 * - Parallel reads that see different objects throw [SourceChangedException]: a response for another
 *   object than the one known replaces it and throws with the id it replaced, and a read that learns of
 *   a change only replaces the known object when no other read has replaced it already.
 *
 * Only a strong ETag is sent as `If-Range`. RFC 9110 section 13.1.5 forbids a weak validator (`W/"..."`)
 * there, so a weak or missing ETag leaves the object unpinned. Such reads still detect a change of
 * size or ETag in the response, but cannot detect a same size rewrite under no ETag. The [id] of such an
 * object is not [SourceId.cacheable], so a shared `FooterCache` keeps nothing for it. Without an ETag
 * its version is unique to this source instance.
 *
 * Nothing is logged, and exception messages carry the URL without its query string.
 * `OpenAqAggregatesFeed` builds one per file and wraps it in a [CachedRangeSource].
 */
class HttpRangeSource(
    private val url: String,
    private val engine: HttpRangeEngine,
    private val openTailBytes: Int = DEFAULT_TAIL_BYTES,
    /** The largest whole object body (any status but 206) this source buffers, see [DEFAULT_MAX_WHOLE_BYTES]. */
    private val maxWholeBytes: Int = DEFAULT_MAX_WHOLE_BYTES,
) : RangeSource {
    private val unversioned = "unversioned-" + Random.nextLong().toULong().toString(16)

    init {
        require(openTailBytes > 0 && maxWholeBytes >= 0)
    }

    /** What the last response said about the object: [tail] is its last `tail.size` bytes. */
    private class Known(val size: Long, val etag: String?, val tail: ByteArray) {
        val tailStart: Long get() = size - tail.size

        /** The ETag to send as `If-Range`, null when missing or weak. */
        val pin: String? get() = etag?.takeUnless { it.startsWith("W/") }

        fun sameObject(other: Known): Boolean = size == other.size && etag == other.etag

        /** Cacheable only under a strong ETag. Without an ETag the version is unique to this source. */
        fun id(uri: String, unversioned: String): SourceId = SourceId(uri, size, etag ?: unversioned, cacheable = pin != null)

        /** [range] from memory, or null when the held tail does not cover it. */
        fun slice(range: LongRange): ByteView? {
            if (range.first < tailStart) return null
            return ByteView(tail, (range.first - tailStart).toInt(), length(range))
        }
    }

    @Volatile
    private var known: Known? = null
    private val updateLock = Mutex()
    private val openLock = Mutex()

    override suspend fun id(): SourceId = open().id(url, unversioned)

    override suspend fun readTail(n: Int): ByteView {
        if (n <= 0) return ByteView.EMPTY
        val previous = known
        if (previous != null) {
            val want = minOf(n.toLong(), previous.size).toInt()
            if (previous.tail.size >= want) return ByteView(previous.tail, previous.tail.size - want, want)
        }
        val response = send("bytes=-$n", n.toLong(), previous)
        if (response.status == 200) {
            val whole = acceptWhole(previous, response)
            val want = minOf(n, whole.size)
            return ByteView(whole, whole.size - want, want)
        }
        val (first, last, total) = contentRange(response)
        val want = minOf(n.toLong(), total)
        val next = Known(total, response.etag ?: previous?.etag, response.body)
        if (previous != null && !previous.sameObject(next)) changed(previous, next)
        if (first != total - want || last != total - 1 || response.body.size.toLong() != want) {
            throw mismatch("bytes=-$n", response)
        }
        adopt(next)
        return ByteView(response.body)
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> {
        if (ranges.all { it.isEmpty() }) return ranges.map { ByteView.EMPTY }
        val size = open().size
        for (r in ranges) {
            if (r.isEmpty()) continue
            if (r.first < 0 || r.last >= size) throw ParquetBoundsException("range $r outside ${redact(url)} of $size bytes")
            length(r)
        }
        return ranges.map { r ->
            if (r.isEmpty()) ByteView.EMPTY else checkNotNull(known).let { it.slice(r) ?: fetch(it, r) }
        }
    }

    private suspend fun fetch(previous: Known, range: LongRange): ByteView {
        val header = "bytes=${range.first}-${range.last}"
        val response = send(header, length(range).toLong(), previous)
        if (response.status == 200) {
            acceptWhole(previous, response)
            return checkNotNull(known).slice(range) ?: throw ParquetBoundsException("range $range outside ${redact(url)}")
        }
        val (first, last, total) = contentRange(response)
        val next = Known(total, response.etag ?: previous.etag, EMPTY)
        if (!previous.sameObject(next)) changed(previous, next)
        if (first != range.first || last != range.last || response.body.size != length(range)) throw mismatch(header, response)
        return ByteView(response.body)
    }

    /** What is known, reading a tail of [openTailBytes] first when nothing is. */
    private suspend fun open(): Known {
        known?.let { return it }
        openLock.withLock { if (known == null) readTail(openTailBytes) }
        return checkNotNull(known)
    }

    /**
     * One GET of [range], [length] bytes at most. A 416 becomes a full GET, any status but 200 and 206
     * is an error. A 206 body may not pass [length], any other body may not pass [maxWholeBytes].
     */
    private suspend fun send(range: String, length: Long, previous: Known?): HttpRangeResponse {
        val whole = maxWholeBytes.toLong()
        val response = engine.get(url, range, previous?.pin, null, BodyLimit(length, whole))
        return when (response.status) {
            200, 206 -> response
            416 -> engine.get(url, null, null, null, BodyLimit(whole, whole)).also { if (it.status != 200) throw HttpRangeException(it.status, url) }
            else -> throw HttpRangeException(response.status, url)
        }
    }

    /** A 200 body is the whole object. Adopts it and throws when it is not the object read before. */
    private suspend fun acceptWhole(previous: Known?, response: HttpRangeResponse): ByteArray {
        val next = Known(response.body.size.toLong(), response.etag, response.body)
        if (previous != null && !previous.sameObject(next)) changed(previous, next)
        adopt(next)
        return response.body
    }

    /**
     * Pins [next] in place of [previous] and throws. A parallel read may have replaced [previous] already,
     * and then its newer pin stays.
     */
    private suspend fun changed(previous: Known, next: Known): Nothing {
        updateLock.withLock { if (known?.sameObject(previous) != false) known = next }
        throw changedFrom(previous, next)
    }

    /**
     * Keeps the longer tail of one object. When a parallel read pinned another object meanwhile, pins
     * [next] and throws with the id it replaced, so no caller mixes bytes of the two.
     */
    private suspend fun adopt(next: Known) {
        val replaced = updateLock.withLock {
            val current = known
            if (current == null || current.tail.size < next.tail.size || !current.sameObject(next)) known = next
            current?.takeUnless { it.sameObject(next) }
        }
        if (replaced != null) throw changedFrom(replaced, next)
    }

    private fun changedFrom(previous: Known, next: Known) =
        SourceChangedException(previous.id(url, unversioned), "${redact(url)} changed from ${previous.size} to ${next.size} bytes")

    private fun contentRange(response: HttpRangeResponse): Triple<Long, Long, Long> {
        val value = response.contentRange?.trim()
            ?: throw HttpRangeException(response.status, url, "206 without Content-Range for ${redact(url)}")
        val match = CONTENT_RANGE.matchEntire(value)
            ?: throw HttpRangeException(response.status, url, "unusable Content-Range '$value' for ${redact(url)}")
        val (first, last, total) = match.destructured
        return Triple(first.toLong(), last.toLong(), total.toLong())
    }

    private fun mismatch(requested: String, response: HttpRangeResponse) = HttpRangeException(
        response.status,
        url,
        "Content-Range '${response.contentRange}' with ${response.body.size} bytes does not answer '$requested' for ${redact(url)}",
    )

    companion object {
        /** The tail [id] reads when nothing is known yet, enough for most footers and page indexes. */
        const val DEFAULT_TAIL_BYTES: Int = 64 * 1024

        /**
         * The default [maxWholeBytes], 32 MiB: far above the files a reader fetches whole on purpose
         * (`RangePolicy.WIFI` does so below 1 MiB) and well under what a phone can hold, so a server that
         * ignores `Range` on a large object fails fast instead of filling memory.
         */
        const val DEFAULT_MAX_WHOLE_BYTES: Int = 32 * 1024 * 1024

        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private val EMPTY = ByteArray(0)

        private fun length(range: LongRange): Int {
            val length = range.last - range.first + 1
            if (length > Int.MAX_VALUE) throw ParquetBoundsException("range $range is longer than 2 GiB")
            return length.toInt()
        }
    }
}
