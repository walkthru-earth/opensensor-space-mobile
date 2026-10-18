package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.ParquetBoundsException
import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import earth.walkthru.aqsync.data.history.parquet.SourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpRangeSourceTest {
    private class Request(val range: String?, val ifRange: String?, val limit: BodyLimit)

    /**
     * Answers from [server], or from [override] when it returns non-null, and records every request.
     * [hold] runs once the response is chosen (with the request's index), so a test can delay it. Like a
     * real engine it refuses a body over the limit.
     */
    private class FakeEngine(
        val server: RangeServer,
        val hold: suspend (Int) -> Unit = {},
        val override: (String?, String?) -> HttpRangeResponse? = { _, _ -> null },
    ) : HttpRangeEngine {
        val requests = ArrayList<Request>()

        override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
            requests += Request(range, ifRange, limit)
            val index = requests.size - 1
            val response = override(range, ifRange) ?: server.respond(range, ifRange)
            hold(index)
            if (response.body.size > limit.of(response.status)) throw bodyTooLarge(response.status, url, limit.of(response.status))
            return response
        }
    }

    private val url = "https://example.test/feed/part.parquet?token=secret"

    @Test
    fun `a suffix read pins the ETag and size with one request`() = runTest {
        val bytes = payload(200_000)
        val engine = FakeEngine(RangeServer(bytes, "\"e1\""))
        val source = HttpRangeSource(url, engine)

        val tail = source.readTail(1000)
        assertContentEquals(bytes.copyOfRange(199_000, 200_000), tail.toByteArray())
        assertEquals(SourceId(url, 200_000, "\"e1\""), source.id())
        assertEquals(1, engine.requests.size)
        assertEquals("bytes=-1000", engine.requests[0].range)
        assertNull(engine.requests[0].ifRange)
    }

    @Test
    fun `id reads a default tail that serves the next short tail with no request`() = runTest {
        val bytes = payload(200_000)
        val engine = FakeEngine(RangeServer(bytes, "\"e1\""))
        val source = HttpRangeSource(url, engine)

        assertEquals(200_000, source.id().size)
        assertEquals("bytes=-${HttpRangeSource.DEFAULT_TAIL_BYTES}", engine.requests.single().range)
        assertContentEquals(bytes.copyOfRange(196_000, 200_000), source.readTail(4000).toByteArray())
        assertContentEquals(bytes.copyOfRange(150_000, 150_010), source.readRanges(listOf(150_000L until 150_010L)).single().toByteArray())
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `a 206 covering a small file serves later ranges with no request`() = runTest {
        val bytes = payload(500)
        val engine = FakeEngine(RangeServer(bytes, "\"e1\""))
        val source = HttpRangeSource(url, engine)

        assertContentEquals(bytes, source.readTail(65_536).toByteArray())
        val views = source.readRanges(listOf(0L until 10L, 100L..199L, 499L..499L))
        assertContentEquals(bytes.copyOfRange(0, 10), views[0].toByteArray())
        assertContentEquals(bytes.copyOfRange(100, 200), views[1].toByteArray())
        assertContentEquals(bytes.copyOfRange(499, 500), views[2].toByteArray())
        assertEquals(SourceId(url, 500, "\"e1\""), source.id())
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `a 200 to a suffix read is the whole object and serves later ranges with no request`() = runTest {
        val bytes = payload(5000)
        val server = RangeServer(bytes, "\"e1\"")
        val engine = FakeEngine(server) { _, _ -> server.whole() }
        val source = HttpRangeSource(url, engine)

        assertContentEquals(bytes.copyOfRange(4000, 5000), source.readTail(1000).toByteArray())
        assertContentEquals(bytes.copyOfRange(10, 20), source.readRanges(listOf(10L until 20L)).single().toByteArray())
        assertEquals(5000, source.id().size)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `range reads send If-Range with the pinned ETag and return exact bytes`() = runTest {
        val bytes = payload(300_000)
        val engine = FakeEngine(RangeServer(bytes, "\"e1\""))
        val source = HttpRangeSource(url, engine)

        source.readTail(1000)
        val views = source.readRanges(listOf(10L until 20L, LongRange.EMPTY, 1000L until 3000L))
        assertContentEquals(bytes.copyOfRange(10, 20), views[0].toByteArray())
        assertEquals(0, views[1].length)
        assertContentEquals(bytes.copyOfRange(1000, 3000), views[2].toByteArray())
        assertEquals(listOf("bytes=-1000", "bytes=10-19", "bytes=1000-2999"), engine.requests.map { it.range })
        assertEquals(listOf(null, "\"e1\"", "\"e1\""), engine.requests.map { it.ifRange })
    }

    @Test
    fun `a 200 for a mismatched If-Range throws SourceChangedException with the old id`() = runTest {
        val server = RangeServer(payload(300_000), "\"e1\"")
        val engine = FakeEngine(server)
        val source = HttpRangeSource(url, engine)
        source.readTail(1000)

        server.bytes = payload(310_000)
        server.etag = "\"e2\""
        val error = assertFailsWith<SourceChangedException> { source.readRanges(listOf(0L until 100L)) }
        assertEquals(SourceId(url, 300_000, "\"e1\""), error.previous)
        assertEquals(false, error.message.orEmpty().contains("secret"))
        assertEquals(SourceId(url, 310_000, "\"e2\""), source.id())
        assertContentEquals(server.bytes.copyOfRange(0, 100), source.readRanges(listOf(0L until 100L)).single().toByteArray())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `a 416 falls back to one full GET`() = runTest {
        val engine = FakeEngine(RangeServer(ByteArray(0), "\"empty\""))
        val source = HttpRangeSource(url, engine)

        assertEquals(0, source.readTail(1000).length)
        assertEquals(SourceId(url, 0, "\"empty\""), source.id())
        assertEquals(listOf("bytes=-1000", null), engine.requests.map { it.range })
        assertEquals(0, source.readTail(10).length)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `a weak ETag is never sent as If-Range`() = runTest {
        val bytes = payload(300_000)
        val engine = FakeEngine(RangeServer(bytes, "W/\"e1\""))
        val source = HttpRangeSource(url, engine)

        source.readTail(1000)
        assertContentEquals(bytes.copyOfRange(5, 15), source.readRanges(listOf(5L until 15L)).single().toByteArray())
        assertEquals(SourceId(url, 300_000, "W/\"e1\"", cacheable = false), source.id())
        assertEquals(listOf<String?>(null, null), engine.requests.map { it.ifRange })
    }

    @Test
    fun `only a strong ETag makes the id cacheable`() = runTest {
        assertEquals(true, HttpRangeSource(url, FakeEngine(RangeServer(payload(1000), "\"e1\""))).id().cacheable)
        val unversioned = HttpRangeSource(url, FakeEngine(RangeServer(payload(1000), null))).id()
        assertEquals(false, unversioned.cacheable)
        assertEquals(1000, unversioned.size)
    }

    @Test
    fun `a 206 may carry the requested length and any other body maxWholeBytes`() = runTest {
        val engine = FakeEngine(RangeServer(payload(300_000), "\"e1\""))
        val source = HttpRangeSource(url, engine, maxWholeBytes = 1_000_000)

        source.readTail(1000)
        source.readRanges(listOf(10L until 20L))
        assertEquals(listOf(1000L, 10L), engine.requests.map { it.limit.partial })
        assertEquals(listOf(1_000_000L, 1_000_000L), engine.requests.map { it.limit.whole })
    }

    @Test
    fun `a whole object over maxWholeBytes is refused`() = runTest {
        val server = RangeServer(payload(5000), "\"e1\"")
        val engine = FakeEngine(server) { _, _ -> server.whole() }
        val error = assertFailsWith<HttpRangeException> { HttpRangeSource(url, engine, maxWholeBytes = 4000).readTail(1000) }
        assertEquals(200, error.status)
        assertEquals(false, error.message.orEmpty().contains("secret"))
    }

    @Test
    fun `the 416 fallback GET is bounded by maxWholeBytes`() = runTest {
        val engine = FakeEngine(RangeServer(ByteArray(0), "\"e1\"")) { range, _ ->
            if (range == null) HttpRangeResponse(200, "\"e1\"", null, null, payload(5000)) else null
        }
        assertFailsWith<HttpRangeException> { HttpRangeSource(url, engine, maxWholeBytes = 4000).readTail(1000) }
        assertEquals(listOf("bytes=-1000", null), engine.requests.map { it.range })
        assertEquals(4000L, engine.requests[1].limit.of(206))
        assertEquals(4000L, engine.requests[1].limit.of(200))
    }

    @Test
    fun `a parallel tail for another object throws SourceChangedException`() = runTest {
        val server = RangeServer(payload(300_000), "\"e1\"")
        val release = CompletableDeferred<Unit>()
        val engine = FakeEngine(server, hold = { if (it == 0) release.await() })
        val source = HttpRangeSource(url, engine)

        val first = async(start = CoroutineStart.UNDISPATCHED) { assertFailsWith<SourceChangedException> { source.readTail(1000) } }
        server.bytes = payload(310_000)
        server.etag = "\"e2\""
        source.readTail(1000)
        release.complete(Unit)
        val error = first.await()
        assertEquals(SourceId(url, 310_000, "\"e2\""), error.previous)
    }

    @Test
    fun `a stale change does not replace a newer one from a parallel read`() = runTest {
        val server = RangeServer(payload(300_000), "\"e1\"")
        val release = CompletableDeferred<Unit>()
        val engine = FakeEngine(server, hold = { if (it == 1) release.await() })
        val source = HttpRangeSource(url, engine)
        source.readTail(1000)

        server.bytes = payload(310_000)
        server.etag = "\"e2\""
        val stale = async(start = CoroutineStart.UNDISPATCHED) { assertFailsWith<SourceChangedException> { source.readRanges(listOf(0L until 100L)) } }
        server.bytes = payload(320_000)
        server.etag = "\"e3\""
        val newer = assertFailsWith<SourceChangedException> { source.readRanges(listOf(0L until 100L)) }
        release.complete(Unit)
        val error = stale.await()

        assertEquals(SourceId(url, 300_000, "\"e1\""), newer.previous)
        assertEquals(SourceId(url, 300_000, "\"e1\""), error.previous)
        assertEquals(SourceId(url, 320_000, "\"e3\""), source.id())
    }

    @Test
    fun `a Content-Range that does not match the request is an error`() = runTest {
        val bytes = payload(300_000)
        val server = RangeServer(bytes, "\"e1\"")
        val engine = FakeEngine(server) { range, ifRange -> if (range == "bytes=10-19") server.respond("bytes=11-20", ifRange) else null }
        val source = HttpRangeSource(url, engine)
        source.readTail(1000)

        val error = assertFailsWith<HttpRangeException> { source.readRanges(listOf(10L until 20L)) }
        assertEquals(206, error.status)
    }

    @Test
    fun `a body shorter than its Content-Range is an error`() = runTest {
        val bytes = payload(300_000)
        val engine = FakeEngine(RangeServer(bytes, "\"e1\"")) { range, _ ->
            if (range == "bytes=10-19") HttpRangeResponse(206, "\"e1\"", "bytes 10-19/300000", null, ByteArray(5)) else null
        }
        val source = HttpRangeSource(url, engine)
        source.readTail(1000)

        assertFailsWith<HttpRangeException> { source.readRanges(listOf(10L until 20L)) }
    }

    @Test
    fun `ranges outside the file throw ParquetBoundsException`() = runTest {
        val engine = FakeEngine(RangeServer(payload(300_000), "\"e1\""))
        val source = HttpRangeSource(url, engine)

        assertFailsWith<ParquetBoundsException> { source.readRanges(listOf(299_990L until 300_001L)) }
        assertFailsWith<ParquetBoundsException> { source.readRanges(listOf(-1L until 10L)) }
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `other statuses throw HttpRangeException without the query string`() = runTest {
        val engine = FakeEngine(RangeServer(payload(10), "\"e1\"")) { _, _ -> HttpRangeResponse(403, null, null, null, ByteArray(0)) }
        val error = assertFailsWith<HttpRangeException> { HttpRangeSource(url, engine).readTail(10) }
        assertEquals(403, error.status)
        assertEquals(false, error.message.orEmpty().contains("secret"))
    }
}
