package earth.walkthru.aqsync.net

import earth.walkthru.aqsync.data.history.parquet.SourceChangedException
import earth.walkthru.aqsync.data.history.parquet.SourceId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [HttpRangeSource] over [OkHttpRangeEngine] against a real socket, MockWebServer serving [RangeServer]. */
class OkHttpRangeEngineTest {
    private val bytes = payload(300_000)
    private val origin = RangeServer(bytes, "\"e1\"")
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().build()
    private val engine = OkHttpRangeEngine(client)

    @Volatile
    private var delaySeconds = 0L

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val answer = origin.respond(request.headers["Range"], request.headers["If-Range"])
                return MockResponse.Builder().code(answer.status).apply {
                    answer.etag?.let { addHeader("ETag", it) }
                    answer.contentRange?.let { addHeader("Content-Range", it) }
                    if (delaySeconds > 0) headersDelay(delaySeconds, TimeUnit.SECONDS)
                }.body(Buffer().write(answer.body)).build()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        client.dispatcher.cancelAll()
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun url() = server.url("/feed/part.parquet").toString()

    @Test
    fun `206 partial reads go through the socket with If-Range and identity encoding`() = runBlocking<Unit> {
        val source = HttpRangeSource(url(), engine)

        assertContentEquals(bytes.copyOfRange(299_000, 300_000), source.readTail(1000).toByteArray())
        assertEquals(SourceId(url(), 300_000, "\"e1\""), source.id())
        val views = source.readRanges(listOf(10L until 20L, 100_000L until 100_500L))
        assertContentEquals(bytes.copyOfRange(10, 20), views[0].toByteArray())
        assertContentEquals(bytes.copyOfRange(100_000, 100_500), views[1].toByteArray())

        assertEquals(listOf("bytes=-1000", "bytes=10-19", "bytes=100000-100499"), requests.map { it.headers["Range"] })
        assertEquals(listOf(null, "\"e1\"", "\"e1\""), requests.map { it.headers["If-Range"] })
        assertTrue(requests.all { it.headers["Accept-Encoding"] == "identity" })
    }

    @Test
    fun `the raw engine maps 206 and 200 and 416`() = runBlocking<Unit> {
        val partial = engine.get(url(), "bytes=0-9", null, null)
        assertEquals(206, partial.status)
        assertEquals("bytes 0-9/300000", partial.contentRange)
        assertEquals("\"e1\"", partial.etag)
        assertContentEquals(bytes.copyOfRange(0, 10), partial.body)

        assertEquals(200, engine.get(url(), "bytes=0-9", "\"other\"", null).status)
        assertEquals(416, engine.get(url(), "bytes=400000-400010", null, null).status)
    }

    @Test
    fun `a short suffix on a small file is served whole with one request`() = runBlocking<Unit> {
        val small = payload(700)
        origin.bytes = small
        val source = HttpRangeSource(url(), engine)

        assertContentEquals(small, source.readTail(65_536).toByteArray())
        assertContentEquals(small.copyOfRange(50, 60), source.readRanges(listOf(50L until 60L)).single().toByteArray())
        assertEquals(700, source.id().size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a 416 on an empty object falls back to a full GET`() = runBlocking<Unit> {
        origin.bytes = ByteArray(0)
        val source = HttpRangeSource(url(), engine)

        assertEquals(0, source.readTail(1000).length)
        assertEquals(0, source.id().size)
        assertEquals(listOf("bytes=-1000", null), requests.map { it.headers["Range"] })
    }

    @Test
    fun `an ETag change mid read throws SourceChangedException`() = runBlocking<Unit> {
        val source = HttpRangeSource(url(), engine)
        source.readTail(1000)

        origin.bytes = payload(320_000)
        origin.etag = "\"e2\""
        val error = assertFailsWith<SourceChangedException> { source.readRanges(listOf(0L until 100L)) }
        assertEquals(SourceId(url(), 300_000, "\"e1\""), error.previous)
        assertEquals("\"e1\"", requests.last().headers["If-Range"])
        assertEquals(SourceId(url(), 320_000, "\"e2\""), source.id())
    }

    @Test
    fun `cancelling the coroutine cancels the call promptly`() = runBlocking<Unit> {
        delaySeconds = 30
        val started = System.nanoTime()
        val read = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { engine.get(url(), "bytes=-10", null, null) }
        withTimeout(5_000) { while (client.dispatcher.runningCallsCount() == 0 || requests.isEmpty()) delay(10) }
        val call = client.dispatcher.runningCalls().single()

        read.cancel()
        withTimeout(5_000) { read.join() }
        assertTrue(read.isCancelled)
        assertTrue(call.isCanceled())
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }
}
