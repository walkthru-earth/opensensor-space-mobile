package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Fixed wire vectors, not just encoder/decoder round trips. Executed on JVM and iOS. */
class PortableProtocolTest {
    @Test fun lanEnvelopeAndHandshakeMatchFirmwareBytes() {
        assertEquals("010007", LanProtocol.encode(Request.Status.encode()).toHex())
        assertEquals("41515331" + "00".repeat(32), LanProtocol.handshake(ByteArray(32)).toHex())
        assertEquals("_aqsync._tcp", LanProtocol.SERVICE_TYPE)
        assertFailsWith<IllegalArgumentException> { LanProtocol.encode(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { LanProtocol.handshake(ByteArray(31)) }
    }

    @Test fun requestWireBytes() {
        assertEquals("03ffffefcdab89ffffffff", Request.Read(65535, 0x89ABCDEF, 0xFFFFFFFF).encode().toHex())
        assertEquals("050807060504030201", Request.SetTime(0x0102030405060708).encode().toHex())
        assertEquals("02436166c3a9", Request.Open("Café").encode().toHex())
        assertEquals("0c0020", Request.LogTail(8192).encode().toHex())
    }

    @Test fun unsignedOpenedFields() {
        val frame = FrameCodec.decode("20ffffefcdab89ffffffff612e70617271756574".hexToBytes()) as Frame.Opened
        assertEquals(65535, frame.handle)
        assertEquals(0x89ABCDEFL, frame.size)
        assertEquals(0xFFFFFFFFL, frame.crc32)
        assertEquals("a.parquet", frame.name)
    }

    @Test fun signedClockAndRssi() {
        val time = FrameCodec.decode("30ffffffffffffffff0000000000000080".hexToBytes()) as Frame.TimeSet
        assertEquals(-1L, time.epochSeconds)
        assertEquals(Long.MIN_VALUE, time.monotonicMicros)
        val ap = FrameCodec.decode("41b00206436166c3a9".hexToBytes()) as Frame.WifiAp
        assertEquals(-80, ap.rssi)
        assertEquals("Café", ap.ssid)
    }

    @Test fun truncatedFixedHeadersFailAsProtocolErrors() {
        val frames = listOf(
            "110100000002000300000004000000", "2001000200000003000000", "21010002000000",
            "2201000200000000", "230100", "3000000000000000000000000000000000",
            "31010002000000", "4000", "41000206", "42010000", "430100", "45010000000200",
            "47030004", "4a0002000000", "7f0102", "4b0104030201f5ff0300100e0000", "4d03",
        )
        for (hex in frames) {
            val complete = hex.hexToBytes()
            for (length in 0 until complete.size) {
                assertFailsWith<FrameDecodeException>("$hex cut at $length") { FrameCodec.decode(complete.copyOf(length)) }
            }
            FrameCodec.decode(complete)
        }
    }

    @Test fun tokenLengthAndHexUnsignedBytes() {
        val token = ByteArray(32) { it.toByte() }
        val frame = FrameCodec.decode(byteArrayOf(0x46, -1, -1) + token) as Frame.Token
        assertEquals(65535, frame.port)
        assertContentEquals(token, frame.token)
        assertEquals("Token(port=65535, token=<redacted>)", frame.toString())
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(byteArrayOf(0x46, 0, 0) + token.copyOf(31)) }
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(byteArrayOf(0x46, 0, 0) + token + byteArrayOf(0)) }
        assertEquals("0080ff", byteArrayOf(0, -128, -1).toHex())
    }

    @Test fun splitAndMalformedUtf8Config() {
        val bytes = "{\"wifi\":{\"ssid\":\"Café\"}}".encodeToByteArray()
        val cut = bytes.indexOf(0xC3.toByte()) + 1
        val assembler = ConfigChunkAccumulator()
        assertNull(assembler.add(Frame.ConfigChunk(1, bytes.size, 0, bytes.copyOfRange(0, cut))))
        assertEquals(bytes.decodeToString(), assembler.add(Frame.ConfigChunk(1, bytes.size, cut, bytes.copyOfRange(cut, bytes.size)))?.json)
        assertFailsWith<IllegalArgumentException> { ConfigChunkAccumulator().add(Frame.ConfigChunk(0, 2, 1, byteArrayOf(1))) }
        assertFailsWith<Exception> { ConfigChunkAccumulator().add(Frame.ConfigChunk(0, 2, 0, byteArrayOf(-61, 40))) }
    }

    @Test fun missingReadingsAndUnknownProfileStayUnavailable() {
        assertNull(LiveSample.parse("{}").pm25)
        assertNull(LiveProfiles.resolve(DeviceInfo(schema = "unreviewed", dict = LiveProfiles.CORES3_DICTIONARY)))
        assertEquals("001234", BleConfig(pin = 1234).pinText)
    }
}
