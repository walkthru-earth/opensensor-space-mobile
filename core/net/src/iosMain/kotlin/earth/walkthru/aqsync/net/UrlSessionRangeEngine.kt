package earth.walkthru.aqsync.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpRangeEngine] on NSURLSession. The data task is cancelled when the calling coroutine is.
 *
 * Every request ignores the local cache (NSURLCache must never answer a range with a stored 206) and
 * sends `Accept-Encoding: identity`, so body offsets are file offsets. Share one engine per process,
 * built with [create] at the app entry point, so connections are reused.
 */
class UrlSessionRangeEngine(private val session: NSURLSession) : HttpRangeEngine {
    override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?): HttpRangeResponse {
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
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                val http = response as? NSHTTPURLResponse
                when {
                    error != null -> continuation.resumeWithException(HttpTransportException("${error.domain} ${error.code} for ${redact(url)}"))
                    http == null -> continuation.resumeWithException(HttpTransportException("no HTTP response for ${redact(url)}"))
                    else -> continuation.resume(
                        HttpRangeResponse(
                            status = http.statusCode.toInt(),
                            etag = http.valueForHTTPHeaderField("ETag"),
                            contentRange = http.valueForHTTPHeaderField("Content-Range"),
                            cacheControl = http.valueForHTTPHeaderField("Cache-Control"),
                            body = data?.toByteArray() ?: ByteArray(0),
                        ),
                    )
                }
            }
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
            }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
