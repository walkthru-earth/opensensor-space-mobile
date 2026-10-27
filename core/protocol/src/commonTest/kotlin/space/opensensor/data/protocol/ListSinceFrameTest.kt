package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `LIST_SINCE` and `LIST_SINCE_END` goldens from docs/shared/ble-sync-protocol.md. */
class ListSinceFrameTest {
    private val requestGolden = "10d4c3b2a1db25350100112233445566778899aabbccddeeff0700000000000000"
    private val endGolden =
        "4e020000000000e80300000a00000002d4c3b2a1dc25350100112233445566778899aabbccddeeff2a00000000000000"

    @Test
    fun `golden request carries the cursor bytes unchanged`() {
        val cursor = ListCursor(requestGolden.hexToBytes().copyOfRange(1, 33))
        val request = Request.ListSince(cursor).encode()
        assertEquals(33, request.size)
        assertEquals(requestGolden, request.toHex())
        assertEquals(0xa1b2c3d4L, cursor.mountEpoch)
        assertEquals("LIST_SINCE", Opcode.name(Opcode.LIST_SINCE))
    }

    @Test
    fun `zero cursor asks for a full listing`() {
        assertEquals("10" + "00".repeat(32), Request.ListSince(ListCursor.ZERO).encode().toHex())
        assertTrue(ListCursor.ZERO.isZero)
        assertEquals(0L, ListCursor.ZERO.mountEpoch)
    }

    @Test
    fun `golden end decodes counters flags and next cursor`() {
        val end = FrameCodec.decode(endGolden.hexToBytes()) as Frame.ListSinceEnd
        assertEquals(2L, end.count)
        assertEquals(0, end.partials)
        assertEquals(1000L, end.sdKib)
        assertEquals(10L, end.sdUsedKib)
        assertFalse(end.full)
        assertTrue(end.unsyncedFull)
        assertEquals(0xa1b2c3d4L, end.next.mountEpoch)
        assertEquals(
            "d4c3b2a1dc25350100112233445566778899aabbccddeeff2a00000000000000",
            end.next.bytes.toHex(),
        )
        assertEquals(Frame.ListEnd(2, 0, 1000, 10), end.toListEnd())
        assertEquals(endGolden, FrameCodec.encode(end).toHex())
        assertEquals("LIST_SINCE_END", FrameType.name(FrameType.LIST_SINCE_END))
    }

    @Test
    fun `end of the wrong length is rejected`() {
        val raw = endGolden.hexToBytes()
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(raw.copyOf(47)) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(raw + 0) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(raw.copyOf(15)) }
    }

    @Test
    fun `full flag is bit 0`() {
        val end = Frame.ListSinceEnd(0, 0, 0, 0, Frame.ListSinceEnd.FLAG_FULL, ListCursor.ZERO)
        assertTrue(end.full)
        assertFalse(end.unsyncedFull)
        assertEquals(end, FrameCodec.decode(FrameCodec.encode(end)))
    }

    @Test
    fun `cursor compares by content and keeps its own copy`() {
        val source = ByteArray(ListCursor.BYTES) { it.toByte() }
        val cursor = ListCursor(source)
        source[0] = 99
        assertEquals(ListCursor(ByteArray(ListCursor.BYTES) { it.toByte() }), cursor)
        cursor.bytes[1] = 99
        assertEquals(1, cursor.bytes[1].toInt())
        assertNotEquals(ListCursor.ZERO, cursor)
        assertFailsWith<IllegalArgumentException> { ListCursor(ByteArray(31)) }
    }

    @Test
    fun `error 11 on op 0x10 means the board has no LIST_SINCE`() {
        val error = FrameCodec.decode("7f100b".hexToBytes()) as Frame.Error
        assertEquals(Opcode.LIST_SINCE, error.op)
        assertEquals(ProtocolError.UNKNOWN_OPCODE, error.error)
    }

    @Test
    fun `status ls is the mount epoch and absent on older firmware`() {
        assertEquals(0xa1b2c3d4L, DeviceStatus.parse("""{"sd":1,"ls":2712847316}""").listSinceEpoch)
        assertNull(DeviceStatus.parse("""{"sd":1}""").listSinceEpoch)
    }
}
