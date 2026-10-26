package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `GET_DESCRIPTOR` and `DESCRIPTOR_CHUNK` goldens from docs/shared/ble-sync-protocol.md. */
class DescriptorFrameTest {
    @Test
    fun `golden request encodes offset 512 and length 1024`() {
        assertEquals("11000200000004", Request.GetDescriptor(offset = 512, length = 1024).encode().toHex())
        assertEquals("GET_DESCRIPTOR", Opcode.name(Opcode.GET_DESCRIPTOR))
    }

    @Test
    fun `request bounds are u32 offset and a length of at least 1`() {
        assertEquals("11ffffffffffff", Request.GetDescriptor(0xFFFF_FFFFL, 0xFFFF).encode().toHex())
        assertFailsWith<IllegalArgumentException> { Request.GetDescriptor(0, 0) }
        assertFailsWith<IllegalArgumentException> { Request.GetDescriptor(0, 0x10000) }
        assertFailsWith<IllegalArgumentException> { Request.GetDescriptor(-1, 1) }
        assertFailsWith<IllegalArgumentException> { Request.GetDescriptor(0x1_0000_0000L, 1) }
    }

    @Test
    fun `golden reply header decodes total and offset`() {
        val body = ByteArray(788) { it.toByte() }
        val raw = "4f1405000000020000".hexToBytes() + body
        val chunk = FrameCodec.decode(raw) as Frame.DescriptorChunk
        assertEquals(1300L, chunk.total)
        assertEquals(512L, chunk.offset)
        assertContentEquals(body, chunk.bytes)
        assertContentEquals(raw, FrameCodec.encode(chunk))
        assertEquals("DESCRIPTOR_CHUNK", FrameType.name(FrameType.DESCRIPTOR_CHUNK))
    }

    @Test
    fun `reply shorter than its header is rejected`() {
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4f14050000000200".hexToBytes()) }
    }

    @Test
    fun `chunk toString leaves out the bytes`() {
        val text = Frame.DescriptorChunk(10, 0, "{}".encodeToByteArray()).toString()
        assertEquals("DescriptorChunk(total=10, offset=0, len=2)", text)
    }

    @Test
    fun `error 17 is no descriptor`() {
        val error = FrameCodec.decode("7f1111".hexToBytes()) as Frame.Error
        assertEquals(ProtocolError.NO_DESCRIPTOR, error.error)
        assertEquals(Opcode.GET_DESCRIPTOR, error.op)
        assertTrue(error.describe().startsWith("GET_DESCRIPTOR failed: no descriptor"))
    }

    @Test
    fun `info carries desc after dict`() {
        val info = DeviceInfo.parse(
            """{"proto":3,"fw":"f","schema":"s","cols":81,"dict":"${"a".repeat(64)}","desc":"0123456789abcdef","station":"x","dev":"d","boot":"b","max_read":16384}""",
        )
        assertEquals("0123456789abcdef", info.desc)
    }
}
