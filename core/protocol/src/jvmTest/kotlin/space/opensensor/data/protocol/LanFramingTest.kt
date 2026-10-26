package space.opensensor.data.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** LAN envelope: `len` u16 LE + body; pushes may sit between any two response frames. */
class LanFramingTest {

    @Test
    fun `encode prefixes little-endian length`() {
        assertEquals("0100 07", spaced(LanFraming.encode(byteArrayOf(0x07))))
        val body = ByteArray(1024) { 0x21 }
        val enc = LanFraming.encode(body)
        assertEquals(0x00, enc[0].toInt() and 0xFF)
        assertEquals(0x04, enc[1].toInt() and 0xFF)
        assertEquals(1026, enc.size)
    }

    @Test
    fun `handshake is OSS1 plus 32 raw token bytes unframed`() {
        val token = ByteArray(32) { it.toByte() }
        val hs = LanFraming.handshake(token)
        assertEquals(36, hs.size)
        assertEquals("OSS1", hs.copyOfRange(0, 4).decodeToString())
        assertArrayEquals(token, hs.copyOfRange(4, 36))
        try {
            LanFraming.handshake(ByteArray(16))
            fail("short token must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `a captured stream with a LIVE push between two CHUNKs demultiplexes in order`() {
        val payload1 = ByteArray(1017) { (it % 251).toByte() } // largest CHUNK payload on LAN
        val payload2 = ByteArray(300) { (it * 3).toByte() }
        val live = Frame.LivePush("""{"seq":15022,"pms":4,"pm25":8}""")
        val status = Frame.StatusPush("""{"up_s":12355,"buf":13}""")
        val wire = ByteArrayOutputStream()
        LanFraming.write(wire, FrameCodec.encode(Frame.Chunk(1, 0, payload1)))
        LanFraming.write(wire, FrameCodec.encode(live))
        LanFraming.write(wire, FrameCodec.encode(Frame.Chunk(1, 1017, payload2)))
        LanFraming.write(wire, FrameCodec.encode(status))
        LanFraming.write(wire, FrameCodec.encode(Frame.ReadEnd(1, 1317, 0)))
        // Every device→phone frame must fit payload_max, including the largest CHUNK.
        assertEquals(1024, 7 + payload1.size)

        val frames = LanFraming.readAll(ByteArrayInputStream(wire.toByteArray()))
        assertEquals(5, frames.size)

        val responses = ArrayList<Frame>()
        val lives = ArrayList<String>()
        val statuses = ArrayList<String>()
        for (raw in frames) {
            when (FrameCodec.typeOf(raw)) {
                FrameType.LIVE -> lives += (FrameCodec.decode(raw) as Frame.LivePush).json
                FrameType.STATUS -> statuses += (FrameCodec.decode(raw) as Frame.StatusPush).json
                else -> responses += FrameCodec.decode(raw)
            }
        }
        assertEquals(listOf(live.json), lives)
        assertEquals(listOf(status.json), statuses)
        assertEquals(3, responses.size)
        val c1 = responses[0] as Frame.Chunk
        val c2 = responses[1] as Frame.Chunk
        val end = responses[2] as Frame.ReadEnd
        assertEquals(0L, c1.offset)
        assertArrayEquals(payload1, c1.payload)
        assertEquals(1017L, c2.offset)
        assertArrayEquals(payload2, c2.payload)
        assertEquals(1317L, end.nextOffset)
        assertEquals(c1.payload.size + c2.payload.size, end.nextOffset.toInt())
        assertTrue(end.ok)
    }

    @Test
    fun `read returns null on clean EOF and fails on a torn frame`() {
        assertNull(LanFraming.read(ByteArrayInputStream(ByteArray(0))))
        try {
            LanFraming.read(ByteArrayInputStream(byteArrayOf(0x05)))
            fail("half a length prefix is not a clean EOF")
        } catch (_: EOFException) {
        }
        try {
            LanFraming.read(ByteArrayInputStream(byteArrayOf(0x05, 0x00, 0x21, 0x01)))
            fail("a frame cut short must not be delivered")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun `zero-length and oversize frames drop the link rather than desync`() {
        try {
            LanFraming.read(ByteArrayInputStream(byteArrayOf(0x00, 0x00)))
            fail("zero-length frame has no type byte")
        } catch (_: FrameDecodeException) {
        }
        val oversize = LanFraming.encode(ByteArray(1025) { 0x44 })
        try {
            LanFraming.read(ByteArrayInputStream(oversize))
            fail("1025 > payload_max")
        } catch (_: FrameDecodeException) {
        }
        // ...but the cap follows HELLO.payload_max when a device advertises more.
        assertEquals(1025, LanFraming.read(ByteArrayInputStream(oversize), maxLen = 2048)!!.size)
    }

    private fun spaced(b: ByteArray): String = b.toHex().let { it.substring(0, 4) + " " + it.substring(4) }
}
