package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protocol 3 goldens from docs/shared/ble-sync-protocol.md ("Protocol 3 contract"), produced by the
 * firmware encoders. Each one decodes field by field and encodes back to the identical bytes.
 */
class FrameCodecV3Test {
    private val eventHex = "78000000070000000302ffff00000200"

    private fun roundTrip(hex: String): Frame {
        val raw = hex.hexToBytes()
        val frame = FrameCodec.decode(raw)
        assertEquals(raw.toHex(), FrameCodec.encode(frame).toHex())
        return frame
    }

    @Test
    fun `disconnect event golden decodes and encodes`() {
        val raw = eventHex.hexToBytes()
        val event = DisconnectEvent.decode(raw)
        assertEquals(DisconnectEvent(120, 7, 3, 2, -1, 0, 2), event)
        assertEquals(DisconnectReason.AUTH_FAILED, event.disconnectReason)
        assertContentEquals(raw, event.encode())
    }

    @Test
    fun `disconnect event decodes at an offset and rejects a short tail`() {
        val raw = ("aabb" + eventHex).hexToBytes()
        assertEquals(120L, DisconnectEvent.decode(raw, 2).uptimeSeconds)
        assertFailsWith<FrameDecodeException> { DisconnectEvent.decode(raw, 3) }
    }

    @Test
    fun `refused LAN client keeps link 255`() {
        val event = DisconnectEvent(1, 0, DisconnectReason.SESSIONS_FULL.code, 255, 0, 0, 1)
        assertEquals(event, DisconnectEvent.decode(event.encode()))
        assertEquals(255, DisconnectEvent.decode(event.encode()).link)
    }

    @Test
    fun `BYE golden decodes and encodes`() {
        val bye = roundTrip("4b0104030201f5ff0300100e0000") as Frame.Bye
        assertEquals(Frame.Bye(1, 0x01020304L, -11, 3, 3600), bye)
        assertEquals(DisconnectReason.IDLE_TIMEOUT, bye.disconnectReason)
    }

    @Test
    fun `BYE with a wrong length is rejected`() {
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4b0104030201f5ff0300100e00".hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4b0104030201f5ff0300100e000000".hexToBytes()) }
    }

    @Test
    fun `DIAG golden with one event decodes and encodes`() {
        val hex = "4c0200" + "0000" + "0500" + "0000" + "0100" + "0000".repeat(13) + "01" + eventHex
        val diag = roundTrip(hex) as Frame.Diag
        assertEquals(2, diag.bootSeq)
        assertEquals(DisconnectReason.COUNT, diag.counts.size)
        assertEquals(5, diag.count(DisconnectReason.IDLE_TIMEOUT))
        assertEquals(1, diag.count(DisconnectReason.AUTH_FAILED))
        assertEquals(6, diag.counts.sum())
        assertEquals(listOf(DisconnectEvent.decode(eventHex.hexToBytes())), diag.events)
    }

    @Test
    fun `DIAG with no events decodes and encodes`() {
        val hex = "4c0200" + "0000".repeat(17) + "00"
        val diag = roundTrip(hex) as Frame.Diag
        assertEquals(FrameCodec.DIAG_HEADER_BYTES, hex.hexToBytes().size)
        assertTrue(diag.events.isEmpty())
    }

    @Test
    fun `DIAG whose event count disagrees with the tail is rejected`() {
        val header = "4c0200" + "0000".repeat(17)
        assertFailsWith<FrameDecodeException> { FrameCodec.decode((header + "02" + eventHex).hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode((header + "00" + eventHex).hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode((header + "01" + eventHex + "00").hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4c0200".hexToBytes()) }
    }

    @Test
    fun `DIAG encoder requires 17 counts`() {
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(Frame.Diag(1, List(16) { 0 }, emptyList())) }
    }

    @Test
    fun `ABORTED goldens decode and encode`() {
        assertEquals(Frame.Aborted(0), roundTrip("4d00"))
        assertEquals(Frame.Aborted(Opcode.READ), roundTrip("4d03"))
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4d".hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("4d0300".hexToBytes()) }
    }

    @Test
    fun `LIST_END golden decodes and encodes`() {
        val end = roundTrip("117011010001000066ed0000040000") as Frame.ListEnd
        assertEquals(Frame.ListEnd(70_000L, 1, 15_558_144L, 1024L), end)
    }

    @Test
    fun `LIST_END round trips counts across the u16 boundary`() {
        for (count in listOf(0L, 65_535L, 65_536L, 0xFFFF_FFFFL)) {
            val frame = Frame.ListEnd(count, 2, 31_000_000L, 12_000L)
            val raw = FrameCodec.encode(frame)
            assertEquals(FrameCodec.LIST_END_BYTES, raw.size)
            assertEquals(frame, FrameCodec.decode(raw))
        }
    }

    @Test
    fun `proto 2 LIST_END of 13 bytes is rejected`() {
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("11 fe02 0000 d084d401 e08e0000".hexToBytes()) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode("117011010001000066ed000004000000".hexToBytes()) }
    }

    @Test
    fun `unknown reason codes map to UNKNOWN without throwing`() {
        assertEquals(DisconnectReason.UNKNOWN, DisconnectReason.fromCode(200))
        assertEquals(DisconnectReason.UNKNOWN, DisconnectReason.fromCode(-1))
        val bye = FrameCodec.decode("4bc804030201f5ff0300100e0000".hexToBytes()) as Frame.Bye
        assertEquals(200, bye.reason)
        assertEquals(DisconnectReason.UNKNOWN, bye.disconnectReason)
    }

    @Test
    fun `reason table matches the contract`() {
        assertEquals(DisconnectReason.COUNT, DisconnectReason.entries.size)
        DisconnectReason.entries.forEachIndexed { i, reason -> assertEquals(i, reason.code) }
        assertEquals("keepalive-failed", DisconnectReason.fromCode(16).wireName)
        assertEquals(ProtocolError.ABORTED, ProtocolError.fromCode(16))
    }

    @Test
    fun `GET_DIAG and ABORT encode to one opcode byte`() {
        assertEquals("0e", Request.GetDiag.encode().toHex())
        assertEquals("0f", Request.Abort.encode().toHex())
        assertEquals("GET_DIAG", Opcode.name(Opcode.GET_DIAG))
        assertEquals("ABORTED", FrameType.name(FrameType.ABORTED))
    }

    @Test
    fun `proto gate asks older and unversioned firmware to update`() {
        assertEquals(ProtoGate.UpdateFirmware(2, 3), requireProto3(DeviceInfo(proto = 2)))
        assertEquals(ProtoGate.UpdateFirmware(null, 3), requireProto3(DeviceInfo()))
    }

    @Test
    fun `proto gate accepts 3 and newer`() {
        assertEquals(ProtoGate.Accepted(3), requireProto3(DeviceInfo(proto = 3)))
        assertEquals(ProtoGate.Accepted(4), requireProto3(DeviceInfo(proto = 4)))
    }

    @Test
    fun `status parses disconnect and card state keys`() {
        val status = DeviceStatus.parse("""{"up_s":5,"dc":4,"dr":12,"sd_state":1}""")
        assertEquals(4, status.dc)
        assertEquals(12, status.dr)
        assertEquals(DisconnectReason.PEER_CLOSED, status.lastDisconnect)
        assertTrue(status.sdDegraded)
        assertFalse(status.sdFull)
        assertTrue(DeviceStatus.parse("""{"sd_state":2}""").sdFull)
    }

    @Test
    fun `status without proto 3 keys keeps them null`() {
        val status = DeviceStatus.parse("""{"up_s":5,"dc":0,"dr":null}""")
        assertEquals(0, status.dc)
        assertNull(status.dr)
        assertNull(status.lastDisconnect)
        assertNull(status.sdState)
        assertFalse(status.sdDegraded)
        val old = DeviceStatus.parse("""{"up_s":5}""")
        assertNull(old.dc)
        assertNull(old.sdState)
    }
}
