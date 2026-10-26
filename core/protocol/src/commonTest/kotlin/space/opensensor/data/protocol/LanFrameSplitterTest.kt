package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The incremental LAN splitter, with the cases of `LanFramingTest` and the iOS `frameBufferChecks`. */
class LanFrameSplitterTest {
    @Test
    fun framesSplitAcrossSlicesComeOutWhole() {
        val frames = LanFrameSplitter()
        frames.append(byteArrayOf(0x03))
        assertNull(frames.next())
        frames.append(byteArrayOf(0x00, 0x21, 0x01))
        assertNull(frames.next())
        frames.append(byteArrayOf(0x02, 0x02, 0x00, 0x07, 0x08, 0x01))
        assertContentEquals(byteArrayOf(0x21, 0x01, 0x02), frames.next())
        assertContentEquals(byteArrayOf(0x07, 0x08), frames.next())
        assertNull(frames.next())
        assertEquals(1, frames.bufferedBytes)
        frames.append(byteArrayOf(0x00, 0x09))
        assertContentEquals(byteArrayOf(0x09), frames.next())
        assertEquals(0, frames.bufferedBytes)
    }

    @Test
    fun oneLargeReceiveCarryingManyFramesKeepsTheirOrder() {
        val batch = ByteArray(200 * 4)
        for (i in 0 until 200) {
            byteArrayOf(0x02, 0x00, 0x22, i.toByte()).copyInto(batch, i * 4)
        }
        val frames = LanFrameSplitter()
        frames.append(batch)
        for (i in 0 until 200) assertContentEquals(byteArrayOf(0x22, i.toByte()), frames.next())
        assertNull(frames.next())
    }

    @Test
    fun aCapturedStreamWithPushesBetweenChunksDemultiplexesInOrderWhateverTheSlicing() {
        val payload1 = ByteArray(1017) { (it % 251).toByte() } // largest CHUNK payload on LAN
        val payload2 = ByteArray(300) { (it * 3).toByte() }
        val sent = listOf(
            FrameCodec.encode(Frame.Chunk(1, 0, payload1)),
            FrameCodec.encode(Frame.LivePush("""{"seq":15022,"pms":4,"pm25":8}""")),
            FrameCodec.encode(Frame.Chunk(1, 1017, payload2)),
            FrameCodec.encode(Frame.StatusPush("""{"up_s":12355,"buf":13}""")),
            FrameCodec.encode(Frame.ReadEnd(1, 1317, 0)),
        )
        val wire = sent.map { LanProtocol.encode(it) }.reduce { a, b -> a + b }
        for (slice in listOf(1, 2, 7, 1026, 4096, wire.size)) {
            val frames = LanFrameSplitter()
            val got = ArrayList<ByteArray>()
            var at = 0
            while (at < wire.size) {
                val n = minOf(slice, wire.size - at)
                frames.append(wire, at, n)
                at += n
                while (true) got += frames.next() ?: break
            }
            assertEquals(sent.size, got.size, "slice $slice")
            sent.zip(got).forEach { (a, b) -> assertContentEquals(a, b, "slice $slice") }
            assertEquals(0, frames.bufferedBytes)
        }
    }

    @Test
    fun zeroLengthAndOversizeFramesDropTheLinkRatherThanDesync() {
        val zero = LanFrameSplitter()
        zero.append(byteArrayOf(0x00, 0x00))
        assertFailsWith<FrameDecodeException> { zero.next() }

        val oversize = LanProtocol.encode(ByteArray(1025) { 0x44 })
        val capped = LanFrameSplitter()
        capped.append(oversize, 0, 2)
        // Refused from the length prefix alone, before the body arrives.
        assertFailsWith<FrameDecodeException> { capped.next() }

        // The cap follows HELLO.payload_max when a device advertises more.
        val wide = LanFrameSplitter(maxLen = 2048)
        wide.append(oversize)
        assertEquals(1025, wide.next()!!.size)
    }

    @Test
    fun aLoweredCapAppliesToTheNextFrame() {
        val frames = LanFrameSplitter()
        frames.maxLen = 4
        frames.append(byteArrayOf(0x05, 0x00))
        assertFailsWith<FrameDecodeException> { frames.next() }
    }

    @Test
    fun streamAndIncrementalReadersShareTheLengthRule() {
        assertEquals(0x0401, LanProtocol.frameLength(0x01, 0x04, 2048))
        assertEquals(0xFFFF, LanProtocol.frameLength(-1, -1, 0xFFFF))
        assertFailsWith<FrameDecodeException> { LanProtocol.frameLength(0, 0, 1024) }
        assertFailsWith<FrameDecodeException> { LanProtocol.frameLength(0x01, 0x04, 1024) }
    }
}
