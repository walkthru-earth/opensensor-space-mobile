package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import earth.walkthru.aqsync.data.history.parquet.SourceId
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSCachedURLResponse
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLCacheStoragePolicy
import platform.Foundation.NSURLProtocol
import platform.Foundation.NSURLProtocolClientProtocol
import platform.Foundation.NSURLProtocolMeta
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.valueForHTTPHeaderField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Answers every request of a session from [StubOrigin.server] with no network, so the test drives
 * NSURLSession, header mapping and [HttpRangeSource] end to end.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class StubUrlProtocol @OverrideInit constructor(
    request: NSURLRequest,
    cachedResponse: NSCachedURLResponse?,
    client: NSURLProtocolClientProtocol?,
) : NSURLProtocol(request, cachedResponse, client) {
    companion object : NSURLProtocolMeta() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean = true

        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }

    override fun startLoading() {
        val request = request
        val headers = mapOf(
            "Range" to request.valueForHTTPHeaderField("Range"),
            "If-Range" to request.valueForHTTPHeaderField("If-Range"),
            "Accept-Encoding" to request.valueForHTTPHeaderField("Accept-Encoding"),
        )
        StubOrigin.requests += headers
        val answer = StubOrigin.server.respond(headers["Range"], headers["If-Range"])
        val fields = buildMap<Any?, Any?> {
            if (!StubOrigin.chunked) put("Content-Length", answer.body.size.toString())
            answer.etag?.let { put("ETag", it) }
            answer.contentRange?.let { put("Content-Range", it) }
        }
        val response = NSHTTPURLResponse(request.URL!!, answer.status.toLong(), "HTTP/1.1", fields)
        val client = client ?: return
        client.URLProtocol(this, didReceiveResponse = response, cacheStoragePolicy = NSURLCacheStoragePolicy.NSURLCacheStorageNotAllowed)
        if (StubOrigin.chunked) {
            for (start in answer.body.indices step CHUNK) {
                client.URLProtocol(this, didLoadData = answer.body.copyOfRange(start, minOf(start + CHUNK, answer.body.size)).toNSData())
            }
        } else {
            client.URLProtocol(this, didLoadData = answer.body.toNSData())
        }
        client.URLProtocolDidFinishLoading(this)
    }

    override fun stopLoading() {}
}

private const val CHUNK = 16 * 1024

/** What [StubUrlProtocol] serves and the headers it saw, reset by each test. */
object StubOrigin {
    var server = RangeServer(ByteArray(0), null)

    /** Leave out Content-Length and deliver the body in 16 KiB pieces. */
    var chunked = false
    val requests = ArrayList<Map<String, String?>>()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

class UrlSessionRangeEngineTest {
    private val url = "https://stub.test/feed/part.parquet"
    private val bytes = payload(300_000)

    private fun engine(): UrlSessionRangeEngine {
        StubOrigin.server = RangeServer(bytes, "\"e1\"")
        StubOrigin.requests.clear()
        StubOrigin.chunked = false
        val configuration = UrlSessionRangeEngine.configuration(allowsExpensive = true, allowsConstrained = true)
        configuration.setProtocolClasses(listOf(StubUrlProtocol))
        return UrlSessionRangeEngine(NSURLSession.sessionWithConfiguration(configuration))
    }

    @AfterTest
    fun reset() {
        StubOrigin.requests.clear()
        StubOrigin.chunked = false
    }

    @Test
    fun `a stalled request gives up after 15 s and a whole read after 30 s`() {
        val c = UrlSessionRangeEngine.configuration(allowsExpensive = true, allowsConstrained = true)
        assertEquals(15.0, c.timeoutIntervalForRequest)
        assertEquals(30.0, c.timeoutIntervalForResource)
    }

    @Test
    fun `206 ranges map Content-Range and ETag and send If-Range and identity encoding`() = runBlocking<Unit> {
        val source = HttpRangeSource(url, engine())

        assertContentEquals(bytes.copyOfRange(299_000, 300_000), source.readTail(1000).toByteArray())
        assertEquals(SourceId(url, 300_000, "\"e1\""), source.id())
        assertContentEquals(bytes.copyOfRange(10, 20), source.readRanges(listOf(10L until 20L)).single().toByteArray())

        assertEquals(listOf("bytes=-1000", "bytes=10-19"), StubOrigin.requests.map { it["Range"] })
        assertEquals(listOf(null, "\"e1\""), StubOrigin.requests.map { it["If-Range"] })
        assertTrue(StubOrigin.requests.all { it["Accept-Encoding"] == "identity" })
    }

    @Test
    fun `a 200 for an If-Range mismatch throws SourceChangedException`() = runBlocking<Unit> {
        val source = HttpRangeSource(url, engine())
        source.readTail(1000)

        StubOrigin.server.bytes = payload(320_000)
        StubOrigin.server.etag = "\"e2\""
        val error = assertFailsWith<SourceChangedException> { source.readRanges(listOf(0L until 100L)) }
        assertEquals(SourceId(url, 300_000, "\"e1\""), error.previous)
        assertEquals(SourceId(url, 320_000, "\"e2\""), source.id())
    }

    @Test
    fun `a 416 on an empty object falls back to a full GET`() = runBlocking<Unit> {
        val engine = engine()
        StubOrigin.server.bytes = ByteArray(0)
        val source = HttpRangeSource(url, engine)

        assertEquals(0, source.readTail(1000).length)
        assertEquals(0, source.id().size)
        assertEquals(listOf("bytes=-1000", null), StubOrigin.requests.map { it["Range"] })
    }

    @Test
    fun `a whole body over maxWholeBytes fails on its Content-Length`() = runBlocking<Unit> {
        val source = HttpRangeSource(url, engine(), maxWholeBytes = 100_000)
        source.readTail(1000)

        StubOrigin.server.etag = "\"e2\""
        val error = assertFailsWith<HttpRangeException> { source.readRanges(listOf(0L until 100L)) }
        assertEquals(200, error.status)
    }

    @Test
    fun `a body without a length is collected in pieces and stops at the limit`() = runBlocking<Unit> {
        val engine = engine()
        StubOrigin.chunked = true

        val whole = engine.get(url, null, null, null, BodyLimit(300_000, 300_000))
        assertEquals(200, whole.status)
        assertContentEquals(bytes, whole.body)

        val error = assertFailsWith<HttpRangeException> { engine.get(url, null, null, null, BodyLimit(100_000, 100_000)) }
        assertEquals(200, error.status)
    }
}
