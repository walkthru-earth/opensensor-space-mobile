package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.ByteView
import earth.walkthru.aqsync.data.history.parquet.RangeSource
import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import earth.walkthru.aqsync.data.history.parquet.SourceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

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
 *
 * Only a strong ETag is sent as `If-Range`. RFC 9110 section 13.1.5 forbids a weak validator (`W/"..."`)
 * there, so a weak or missing ETag leaves the object unpinned. Such reads still detect a change of
 * size or ETag in the response, but cannot detect a same size rewrite under no ETag.
 *
 * Nothing is logged, and exception messages carry the URL without its query string.
 * A disk `BlockCache` is out of scope until issues #60 and #61 land.
 */
class HttpRangeSource(
    private val url: String,
    private val engine: HttpRangeEngine,
    private val openTailBytes: Int = DEFAULT_TAIL_BYTES,
) : RangeSource {
    init {
        require(openTailBytes > 0)
    }

    /** What the last response said about the object: [tail] is its last `tail.size` bytes. */
    private class Known(val size: Long, val etag: String?, val tail: ByteArray) {
        val tailStart: Long get() = size - tail.size

        /** The ETag to send as `If-Range`, null when missing or weak. */
        val pin: String? get() = etag?.takeUnless { it.startsWith("W/") }

        fun sameObject(other: Known): Boolean = size == other.size && etag == other.etag

        fun id(uri: String): SourceId = SourceId(uri, size, etag ?: "")

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

    override suspend fun id(): SourceId = open().id(url)

    override suspend fun readTail(n: Int): ByteView {
        if (n <= 0) return ByteView.EMPTY
        val previous = known
        if (previous != null) {
            val want = minOf(n.toLong(), previous.size).toInt()
            if (previous.tail.size >= want) return ByteView(previous.tail, previous.tail.size - want, want)
        }
        val response = send("bytes=-$n", previous)
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
            if (r.first < 0 || r.last >= size) throw IndexOutOfBoundsException("range $r outside ${redact(url)} of $size bytes")
            length(r)
        }
        return ranges.map { r ->
            if (r.isEmpty()) ByteView.EMPTY else checkNotNull(known).let { it.slice(r) ?: fetch(it, r) }
        }
    }

    private suspend fun fetch(previous: Known, range: LongRange): ByteView {
        val header = "bytes=${range.first}-${range.last}"
        val response = send(header, previous)
        if (response.status == 200) {
            acceptWhole(previous, response)
            return checkNotNull(known).slice(range) ?: throw IndexOutOfBoundsException("range $range outside ${redact(url)}")
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

    /** One GET. A 416 becomes a full GET, any status but 200 and 206 is an error. */
    private suspend fun send(range: String, previous: Known?): HttpRangeResponse {
        val response = engine.get(url, range, previous?.pin, null)
        return when (response.status) {
            200, 206 -> response
            416 -> engine.get(url, null, null, null).also { if (it.status != 200) throw HttpRangeException(it.status, url) }
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

    private suspend fun changed(previous: Known, next: Known): Nothing {
        updateLock.withLock { known = next }
        throw SourceChangedException(previous.id(url), "${redact(url)} changed from ${previous.size} to ${next.size} bytes")
    }

    /** Keeps the longer tail of one object, or the newer object. */
    private suspend fun adopt(next: Known) {
        updateLock.withLock {
            val current = known
            if (current == null || !current.sameObject(next) || current.tail.size < next.tail.size) known = next
        }
    }

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

        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private val EMPTY = ByteArray(0)

        private fun length(range: LongRange): Int {
            val length = range.last - range.first + 1
            if (length > Int.MAX_VALUE) throw IndexOutOfBoundsException("range $range is longer than 2 GiB")
            return length.toInt()
        }
    }
}
