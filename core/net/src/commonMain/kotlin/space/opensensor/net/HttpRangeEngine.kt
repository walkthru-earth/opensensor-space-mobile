package space.opensensor.net

/**
 * One HTTP GET with the conditional and range headers a [HttpRangeSource] needs. Each platform
 * binds it once (OkHttp on Android and the JVM, NSURLSession on iOS). An engine sends exactly the
 * headers it is given plus `Accept-Encoding: identity`, and never serves a response from a local HTTP
 * cache. It throws for transport failures and for a body over [limit], any other status is returned.
 */
interface HttpRangeEngine {
    /**
     * GETs [url]. [range] is the raw `Range` value (`bytes=-65536`, `bytes=10-20`), null for a full
     * GET. [ifRange] and [ifNoneMatch] are raw ETags, null to leave the header out. The body is read
     * incrementally and never past [limit] for the response status: a `Content-Length` over it fails
     * before any body byte is read, and a body without one fails as soon as it passes the limit.
     */
    suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse
}

/**
 * The most body bytes an engine buffers for one response. A 206 answers exactly the requested range,
 * so [partial] is that range's length. Any other status may carry the whole object (a server that
 * ignores `Range`, an `If-Range` mismatch, a full GET), bounded by [whole].
 */
class BodyLimit(val partial: Long, val whole: Long) {
    init {
        require(partial in 0..Int.MAX_VALUE && whole in 0..Int.MAX_VALUE)
    }

    /** The limit for a response with [status]. */
    fun of(status: Int): Long = if (status == 206) partial else whole
}

/** The parts of a response a range reader uses. Header values are raw, null when absent. */
class HttpRangeResponse(
    val status: Int,
    val etag: String?,
    val contentRange: String?,
    val cacheControl: String?,
    val body: ByteArray,
)

/** The server answered [status], which a range read cannot use. The message carries no query string. */
class HttpRangeException(val status: Int, val url: String, message: String = "HTTP $status for ${redact(url)}") : RuntimeException(message)

/** The [status] response body is longer than [limit] bytes, so the engine stopped reading it. */
internal fun bodyTooLarge(status: Int, url: String, limit: Long): HttpRangeException =
    HttpRangeException(status, url, "HTTP $status body over $limit bytes for ${redact(url)}")

/** The request failed before a status arrived (DNS, TLS, connection, timeout). */
class HttpTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** [url] without its query string or fragment, the only form that may reach a message. */
fun redact(url: String): String = url.substringBefore('#').substringBefore('?')
