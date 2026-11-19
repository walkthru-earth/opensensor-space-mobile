package space.opensensor

import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncWireDataTest {
    private val wire = SyncWire()

    @Test fun binaryBridgeRoundTripsWithoutHex() {
        val bytes = ByteArray(1017) { it.toByte() }
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
        assertEquals(0uL, ByteArray(0).toNSData().length)
        assertContentEquals(ByteArray(0), ByteArray(0).toNSData().toByteArray())
    }

    @Test fun nsDataFacadeMatchesCanonicalFrames() {
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x07), wire.envelope(wire.statusRequest()).toByteArray())
        val chunk = wire.decode(byteArrayOf(0x21, 0x34, 0x12, 4, 3, 2, 1, 1, 2, 3).toNSData())!!
        assertEquals("chunk", chunk.kind)
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.payload.toByteArray())
        assertNull(wire.decode(ByteArray(0).toNSData()))
        val token = ByteArray(32) { 0xab.toByte() }
        val handshake = wire.handshake(token.toNSData()).toByteArray()
        assertEquals(36, handshake.size)
        assertContentEquals(token, handshake.copyOfRange(4, 36))
    }

    @Test fun frameKindReadsTheTypeByteInPlace() {
        assertEquals(FrameKind.REPLY, wire.frameKind(byteArrayOf(0x21, 0x34, 0x12, 4, 3, 2, 1, 1, 2, 3).toNSData()))
        assertEquals(FrameKind.STATUS, wire.frameKind(byteArrayOf(0x48, 0x7b, 0x7d).toNSData()))
        assertEquals(FrameKind.LIVE, wire.frameKind(byteArrayOf(0x49, 0x7b, 0x7d).toNSData()))
        assertEquals(FrameKind.INVALID, wire.frameKind(byteArrayOf(0x50).toNSData()))
        assertEquals(FrameKind.INVALID, wire.frameKind(ByteArray(0).toNSData()))
    }

    @Test fun helloCheckTakesTheFrameBody() {
        val json = """{"proto":3,"thing":"abc-123","dev":"aabbccddeeff"}"""
        val hello = FrameCodec.encode(Frame.Hello(3, 1024, json)).toNSData()
        assertEquals(HelloVerdict.ACCEPTED, wire.helloCheck(hello, thing = "abc-123", device = "aabbccddeeff").verdict)
        assertEquals(HelloVerdict.THING_CHANGED, wire.helloCheck(hello, thing = "other", device = "aabbccddeeff").verdict)
    }
}
