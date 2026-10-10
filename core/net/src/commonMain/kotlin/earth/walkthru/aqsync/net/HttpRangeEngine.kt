package earth.walkthru.aqsync.net

/**
 * One HTTP GET with the conditional and range headers a [HttpRangeSource] needs. Each platform
 * binds it once (OkHttp on Android and the JVM, NSURLSession on iOS). An engine sends exactly the
 * headers it is given plus `Accept-Encoding: identity`, never serves a response from a local HTTP
 * cache, and reads the whole body. It throws only for transport failures, any status is returned.
 */
interface HttpRangeEngine {
    /**
     * GETs [url]. [range] is the raw `Range` value (`bytes=-65536`, `bytes=10-20`), null for a full
     * GET. [ifRange] and [ifNoneMatch] are raw ETags, null to leave the header out.
     */
    suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?): HttpRangeResponse
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

/** The request failed before a status arrived (DNS, TLS, connection, timeout). */
class HttpTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** [url] without its query string or fragment, the only form that may reach a message. */
fun redact(url: String): String = url.substringBefore('#').substringBefore('?')
