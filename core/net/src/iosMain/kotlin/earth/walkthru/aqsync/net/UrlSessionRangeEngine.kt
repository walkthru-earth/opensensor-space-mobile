package earth.walkthru.aqsync.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpRangeEngine] on NSURLSession. The data task is cancelled when the calling coroutine is.
 *
 * Every request ignores the local cache (NSURLCache must never answer a range with a stored 206) and
 * sends `Accept-Encoding: identity`, so body offsets are file offsets. Share one engine per process,
 * built with [create] at the app entry point, so connections are reused.
 *
 * Each task gets its own delegate ([BodyCollector]) instead of a completion handler, so the body is
 * copied once, chunk by chunk, into the array the response carries, and the task is cancelled as soon as
 * the body passes its [BodyLimit].
 */
class UrlSessionRangeEngine(private val session: NSURLSession) : HttpRangeEngine {
    override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
        val target = NSURL.URLWithString(url) ?: throw IllegalArgumentException("not a URL ${redact(url)}")
        val request = NSMutableURLRequest.requestWithURL(target).apply {
            setHTTPMethod("GET")
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setValue("identity", forHTTPHeaderField = "Accept-Encoding")
            if (range != null) setValue(range, forHTTPHeaderField = "Range")
            if (ifRange != null) setValue(ifRange, forHTTPHeaderField = "If-Range")
            if (ifNoneMatch != null) setValue(ifNoneMatch, forHTTPHeaderField = "If-None-Match")
        }
        return suspendCancellableCoroutine { continuation ->
            val task = session.dataTaskWithRequest(request)
            task.setDelegate(BodyCollector(url, limit, continuation))
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    companion object {
        /**
         * An engine on an ephemeral session with no URL cache. [allowsExpensive] and [allowsConstrained]
         * follow the user's setting for cellular or hotspot data and for Low Data Mode.
         */
        fun create(allowsExpensive: Boolean, allowsConstrained: Boolean): UrlSessionRangeEngine =
            UrlSessionRangeEngine(NSURLSession.sessionWithConfiguration(configuration(allowsExpensive, allowsConstrained)))

        /** The configuration [create] uses. Tests add a stub protocol class to it. */
        internal fun configuration(allowsExpensive: Boolean, allowsConstrained: Boolean): NSURLSessionConfiguration =
            NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
                setAllowsExpensiveNetworkAccess(allowsExpensive)
                setAllowsConstrainedNetworkAccess(allowsConstrained)
                setURLCache(null)
                setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                // A stalled request gives up after 15 s and a whole read after 30 s, not the 60 s and
                // 7 day defaults, so a dead network ends in an error with Retry.
                setTimeoutIntervalForRequest(15.0)
                setTimeoutIntervalForResource(30.0)
            }
    }
}

/**
 * The delegate of one data task. It sizes the body from `Content-Length` (refusing one over the limit
 * before any data), appends each chunk into it, and resumes [continuation] exactly once. The session
 * calls it on its serial delegate queue, so its state needs no lock.
 */
@OptIn(ExperimentalForeignApi::class)
private class BodyCollector(
    private val url: String,
    private val limit: BodyLimit,
    private val continuation: CancellableContinuation<HttpRangeResponse>,
) : NSObject(),
    NSURLSessionDataDelegateProtocol {
    private var head: NSHTTPURLResponse? = null
    private var max = 0L
    private var body = ByteArray(0)
    private var count = 0
    private var finished = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val http = didReceiveResponse as? NSHTTPURLResponse
        if (http == null) {
            fail(HttpTransportException("no HTTP response for ${redact(url)}"))
            return completionHandler(NSURLSessionResponseCancel)
        }
        val status = http.statusCode.toInt()
        max = limit.of(status)
        // expectedContentLength is -1 when the length is unknown (chunked).
        val expected = http.expectedContentLength
        if (expected > max) {
            fail(bodyTooLarge(status, url, max))
            return completionHandler(NSURLSessionResponseCancel)
        }
        head = http
        body = ByteArray(if (expected >= 0) expected.toInt() else minOf(max, INITIAL_BYTES).toInt())
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (finished) return
        val n = didReceiveData.length.toLong()
        if (n == 0L) return
        if (count + n > max) {
            fail(bodyTooLarge(checkNotNull(head).statusCode.toInt(), url, max))
            return dataTask.cancel()
        }
        if (count + n > body.size) body = body.copyOf(minOf(max, maxOf(count + n, body.size * 2L)).toInt())
        body.usePinned { memcpy(it.addressOf(count), didReceiveData.bytes, didReceiveData.length) }
        count += n.toInt()
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (finished) return
        val http = head
        when {
            didCompleteWithError != null -> fail(HttpTransportException("${didCompleteWithError.domain} ${didCompleteWithError.code} for ${redact(url)}"))
            http == null -> fail(HttpTransportException("no HTTP response for ${redact(url)}"))
            else -> {
                finished = true
                continuation.resume(
                    HttpRangeResponse(
                        status = http.statusCode.toInt(),
                        etag = http.valueForHTTPHeaderField("ETag"),
                        contentRange = http.valueForHTTPHeaderField("Content-Range"),
                        cacheControl = http.valueForHTTPHeaderField("Cache-Control"),
                        body = if (count == body.size) body else body.copyOf(count),
                    ),
                )
            }
        }
    }

    private fun fail(error: Throwable) {
        if (finished) return
        finished = true
        continuation.resumeWithException(error)
    }
}

/** The first allocation for a body of unknown length, doubled as it grows. */
private const val INITIAL_BYTES = 64L * 1024
