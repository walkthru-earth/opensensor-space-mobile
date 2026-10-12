package earth.walkthru.aqsync.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpRangeEngine] on OkHttp 5, for Android and the JVM. The call is enqueued on the client's
 * dispatcher and cancelled when the calling coroutine is.
 *
 * Share one long-lived [client] per process (for example [newClient], built once at the app entry
 * point), so HTTP/2 connections and the dispatcher are reused across files.
 *
 * Every request sends `Accept-Encoding: identity`. Without it OkHttp asks for gzip and inflates the
 * body transparently, and byte offsets of a gzip encoded body are not offsets in the file.
 */
class OkHttpRangeEngine(private val client: OkHttpClient) : HttpRangeEngine {
    override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
        val request = Request.Builder().url(url).get().header("Accept-Encoding", "identity").apply {
            if (range != null) header("Range", range)
            if (ifRange != null) header("If-Range", ifRange)
            if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
        }.build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                val body = readBody(it, url, limit.of(it.code))
                                HttpRangeResponse(it.code, it.header("ETag"), it.header("Content-Range"), it.header("Cache-Control"), body)
                            }
                        }
                        result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                },
            )
        }
    }

    /**
     * The body, at most [max] bytes. A larger `Content-Length` fails before reading, and a body without
     * one is buffered to `max + 1` bytes at most, so an oversized body fails without being read whole.
     */
    private fun readBody(response: Response, url: String, max: Long): ByteArray {
        val body = response.body
        if (body.contentLength() > max) throw bodyTooLarge(response.code, url, max)
        val source = body.source()
        if (source.request(max + 1)) throw bodyTooLarge(response.code, url, max)
        return source.buffer.readByteArray()
    }

    companion object {
        /**
         * A client for range reads. Build it once per process and share it. A stalled read gives up
         * after 15 s and a whole call after 30 s, like the iOS engine, so a dead network ends in an error
         * with Retry instead of a long spinner.
         */
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
