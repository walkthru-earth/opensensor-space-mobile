package earth.walkthru.aqsync.data.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame layouts from docs/ble-sync-protocol.md v1. Hex fixtures are hand-assembled from the
 * documented byte layout (little-endian) and cross-checked against what the CoreS3 firmware
 * `arduino-cores3-parquet-v4` emitted on 2026-09-16.
 */
class FrameCodecTest {

    @Test
    fun `requests encode per contract`() {
        assertEquals("01", Request.List.encode().toHex())
        assertEquals("06", Request.Flush.encode().toHex())
        assertEquals("07", Request.Status.encode().toHex())
        // OPEN: opcode + utf8 name
        assertEquals("02" + "a.parquet".toByteArray().toHex(), Request.Open("a.parquet").encode().toHex())
        // READ handle=1 offset=0x4000 length=16384 -> 03 0100 00400000 00400000
        assertEquals("03" + "0100" + "00400000" + "00400000", Request.Read(1, 0x4000, 16384).encode().toHex())
        // CLOSE handle=0x0102 -> 04 0201
        assertEquals("040201", Request.Close(0x0102).encode().toHex())
        // SET_TIME epoch 1789581931 = 0x6AAA_DA6B -> 05 6bdaaa6a00000000
        assertEquals("056bdaaa6a00000000", Request.SetTime(1_789_581_931L).encode().toHex())
    }

    @Test
    fun `FILE frame decodes name and size`() {
        val name = "station=53315f5f/unsynced/boot=b41d/data_unsynced_b41d_0-26-0.parquet"
        val raw = ("10" + "39490000" + name.toByteArray().toHex()).hexToBytes() // 0x4939 = 18745
        val frame = FrameCodec.decode(raw) as Frame.File
        assertEquals(18745L, frame.bytes)
        assertEquals(name, frame.name)
    }

    @Test
    fun `LIST_END decodes counts and card size`() {
        // count=766 (0x02FE) partials=0 sd_kib=30704848 (0x01D48CD0) used=36576 (0x8EE0)
        val frame = FrameCodec.decode("11 fe02 0000 d084d401 e08e0000".hexToBytes()) as Frame.ListEnd
        assertEquals(766, frame.count)
        assertEquals(0, frame.partials)
        assertEquals(30_704_848L, frame.sdKib)
        assertEquals(36_576L, frame.sdUsedKib)
    }

    @Test
    fun `OPENED decodes handle size crc and echoed name`() {
        // handle=1 size=14080 (0x3700) crc32=0xd4c4189c
        val raw = ("20" + "0100" + "00370000" + "9c18c4d4" + "x.parquet".toByteArray().toHex()).hexToBytes()
        val frame = FrameCodec.decode(raw) as Frame.Opened
        assertEquals(1, frame.handle)
        assertEquals(14080L, frame.size)
        assertEquals(0xd4c4189cL, frame.crc32)
        assertEquals("x.parquet", frame.name)
    }

    @Test
    fun `CHUNK carries absolute offset and payload`() {
        val raw = ("21" + "0100" + "f9010000" + "50415231").hexToBytes() // offset 505, payload PAR1
        val frame = FrameCodec.decode(raw) as Frame.Chunk
        assertEquals(1, frame.handle)
        assertEquals(505L, frame.offset)
        assertArrayEquals("PAR1".toByteArray(), frame.payload)
    }

    @Test
    fun `READ_END status zero is ok and non-zero maps to error table`() {
        val ok = FrameCodec.decode("22 0100 00370000 00".hexToBytes()) as Frame.ReadEnd
        assertTrue(ok.ok)
        assertEquals(14080L, ok.nextOffset)
        val busy = FrameCodec.decode("22 0100 00100000 07".hexToBytes()) as Frame.ReadEnd
        assertEquals(ProtocolError.BUSY, ProtocolError.fromCode(busy.status))
    }

    @Test
    fun `TIME_SET FLUSHED CLOSED decode`() {
        val time = FrameCodec.decode("30 6bdaaa6a00000000 e1ac031100000000".hexToBytes()) as Frame.TimeSet
        assertEquals(1_789_581_931L, time.epochSeconds)
        assertEquals(285_453_537L, time.monotonicMicros)
        val flushed = FrameCodec.decode("31 0200 02000000".hexToBytes()) as Frame.Flushed
        assertEquals(2, flushed.rows)
        assertEquals(2L, flushed.filesFinalized)
        val closed = FrameCodec.decode("23 0500".hexToBytes()) as Frame.Closed
        assertEquals(5, closed.handle)
    }

    @Test
    fun `ERROR decodes op code and detail`() {
        val raw = ("7f" + "02" + "03" + "missing.parquet".toByteArray().toHex()).hexToBytes()
        val frame = FrameCodec.decode(raw) as Frame.Error
        assertEquals(Opcode.OPEN, frame.op)
        assertEquals(ProtocolError.NOT_FINALIZED_PARQUET, frame.error)
        assertEquals("missing.parquet", frame.detail)
        assertTrue(frame.describe().contains("OPEN"))
    }

    @Test
    fun `every frame survives an encode decode round trip`() {
        val frames = listOf(
            Frame.File(41_986, "station=a/unsynced/boot=b/data.parquet"),
            Frame.ListEnd(766, 0, 30_704_848, 36_576),
            Frame.Opened(7, 28_537, 0x3a5f3ba1L, "benchmarks/boot=e4/u.parquet"),
            Frame.Chunk(7, 16_384, ByteArray(505) { it.toByte() }),
            Frame.ReadEnd(7, 28_537, 0),
            Frame.Closed(7),
            Frame.TimeSet(1_789_581_931L, 285_464_289L),
            Frame.Flushed(2, 2),
            Frame.Error(Opcode.FLUSH, ProtocolError.NOTHING_TO_FLUSH.code, ""),
        )
        for (frame in frames) {
            val decoded = FrameCodec.decode(FrameCodec.encode(frame))
            if (frame is Frame.Chunk) {
                val d = decoded as Frame.Chunk
                assertEquals(frame.handle, d.handle)
                assertEquals(frame.offset, d.offset)
                assertArrayEquals(frame.payload, d.payload)
            } else {
                assertEquals(frame, decoded)
            }
        }
    }

    @Test(expected = FrameDecodeException::class)
    fun `truncated frame is rejected not guessed`() {
        FrameCodec.decode("20 0100 0037".hexToBytes())
    }

    @Test(expected = FrameDecodeException::class)
    fun `unknown frame type is rejected`() {
        FrameCodec.decode("99 00".hexToBytes())
    }
}
