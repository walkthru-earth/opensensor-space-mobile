package space.opensensor.data.ble

import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkTimeoutException
import space.opensensor.link.TimeoutPhase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DescriptorDownloadTest {
    private val template = ByteArray(1300) { (it * 7).toByte() }

    /** Serves [bytes] like the firmware, clamped to [max] bytes per chunk, and logs each request. */
    private class Board(var bytes: ByteArray, val max: Int) {
        val asked = mutableListOf<Pair<Long, Int>>()
        fun chunk(offset: Long, length: Int): Frame.DescriptorChunk {
            asked += offset to length
            val n = minOf(length.toLong(), bytes.size - offset, max.toLong()).toInt()
            return Frame.DescriptorChunk(bytes.size.toLong(), offset, bytes.copyOfRange(offset.toInt(), offset.toInt() + n))
        }
    }

    @Test
    fun `chunk length is payload max minus the header`() {
        assertEquals(503, DescriptorDownload.chunkLength(512))
        assertEquals(1015, DescriptorDownload.chunkLength(1024))
        assertEquals(1, DescriptorDownload.chunkLength(9))
    }

    @Test
    fun `chunks reassemble in order`() = runTest {
        val board = Board(template, max = 503)
        val bytes = DescriptorDownload.fetch(503) { o, l -> board.chunk(o, l) }
        assertContentEquals(template, bytes)
        assertEquals(listOf(0L, 503L, 1006L), board.asked.map { it.first })
    }

    @Test
    fun `a clamped chunk asks again from the next missing offset`() = runTest {
        val board = Board(template, max = 200)
        val bytes = DescriptorDownload.fetch(1015) { o, l -> board.chunk(o, l) }
        assertContentEquals(template, bytes)
        assertEquals(7, board.asked.size)
    }

    @Test
    fun `a chunk for another offset is dropped and asked again`() = runTest {
        val board = Board(template, max = 503)
        var stale = true
        val bytes = DescriptorDownload.fetch(503) { o, l ->
            if (o == 503L && stale) {
                stale = false
                board.chunk(0, l)
            } else {
                board.chunk(o, l)
            }
        }
        assertContentEquals(template, bytes)
        // The board log shows the stale chunk it sent for offset 0 in place of 503.
        assertEquals(listOf(0L, 0L, 503L, 1006L), board.asked.map { it.first })
    }

    @Test
    fun `endless lost replies give up`() = runTest {
        assertFailsWith<LinkTimeoutException> {
            DescriptorDownload.fetch(503) { _, _ -> throw LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.FIRST_FRAME, Opcode.GET_DESCRIPTOR)) }
        }
    }

    @Test
    fun `a lost reply asks again for the same offset`() = runTest {
        val board = Board(template, max = 503)
        var lose = true
        val bytes = DescriptorDownload.fetch(503) { o, l ->
            if (o == 503L && lose) {
                lose = false
                throw LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.FIRST_FRAME, Opcode.GET_DESCRIPTOR))
            }
            board.chunk(o, l)
        }
        assertContentEquals(template, bytes)
    }

    @Test
    fun `a total change restarts once from offset 0`() = runTest {
        val board = Board(template, max = 503)
        val next = ByteArray(900) { 3 }
        val bytes = DescriptorDownload.fetch(503) { o, l ->
            if (o == 503L && board.bytes === template) board.bytes = next
            board.chunk(o, l)
        }
        assertContentEquals(next, bytes)
        assertEquals(listOf(0L, 503L, 0L, 503L), board.asked.map { it.first })
    }

    @Test
    fun `a second total change fails the download`() = runTest {
        val other = ByteArray(900) { 3 }
        val board = Board(template, max = 503)
        assertFailsWith<DescriptorDownloadException> {
            DescriptorDownload.fetch(503) { o, l ->
                // A second restart would ask a fifth time, which fails with IllegalStateException.
                check(board.asked.size < 4) { "asked past the second total change" }
                if (o == 503L) board.bytes = if (board.bytes === template) other else template
                board.chunk(o, l)
            }
        }
        assertEquals(listOf(0L, 503L, 0L, 503L), board.asked.map { it.first })
    }

    @Test
    fun `an endless stream of foreign chunks gives up`() = runTest {
        val board = Board(template, max = 503)
        assertFailsWith<DescriptorDownloadException> {
            DescriptorDownload.fetch(503) { o, l ->
                val chunk = board.chunk(o, l)
                if (o == 0L) chunk else Frame.DescriptorChunk(chunk.total, 1, chunk.bytes)
            }
        }
    }

    @Test
    fun `an empty or oversized template is refused`() = runTest {
        assertFailsWith<DescriptorDownloadException> {
            DescriptorDownload.fetch(503) { o, _ -> Frame.DescriptorChunk(0, o, ByteArray(0)) }
        }
        assertFailsWith<DescriptorDownloadException> {
            DescriptorDownload.fetch(503) { o, _ -> Frame.DescriptorChunk(DescriptorDownload.MAX_BYTES + 1L, o, ByteArray(1)) }
        }
    }

    @Test
    fun `a chunk past total is refused`() = runTest {
        assertFailsWith<DescriptorDownloadException> {
            DescriptorDownload.fetch(503) { o, _ -> Frame.DescriptorChunk(4, o, ByteArray(5)) }
        }
    }
}
