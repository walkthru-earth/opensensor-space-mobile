package earth.walkthru.opensensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncWireTest {
    private val wire = SyncWire()

    @Test fun requestsUseCanonicalLittleEndianCodec() {
        assertEquals("0d", wire.tokenFrame().hex())
        assertEquals("01", wire.listFrame().hex())
        assertEquals("07", wire.statusFrame().hex())
        assertEquals("0334120403020110000000", wire.readFrame(0x1234, 0x01020304, 16).hex())
        assertEquals("043412", wire.closeFrame(0x1234).hex())
        assertEquals("010001", wire.envelopeFrame(wire.listFrame()).hex())
    }

    @Test fun strictDecodeAndSecretRedaction() {
        val token = wire.decodeHex("46" + "1eba" + "ab".repeat(32))!!
        assertEquals("token", token.kind)
        assertEquals(47646, token.port)
        assertEquals("ab".repeat(32), token.bytes.hex())
        assertFalse(token.toString().contains("abab"))
        assertNull(wire.decodeHex("461e"))
        assertNull(wire.decodeHex("21"))
        assertNull(wire.decodeFrame(ByteArray(0)))
        val chunk = wire.decodeHex("21341204030201010203")!!
        assertEquals("chunk", chunk.kind)
        assertEquals(0x1234, chunk.handle)
        assertEquals(0x01020304, chunk.offset)
        assertEquals("010203", chunk.bytes.hex())
    }

    @Test fun handshakeCarriesOnlyTheRawToken() {
        val token = ByteArray(32) { it.toByte() }
        val frame = wire.handshakeFrame(token)
        assertEquals(36, frame.size)
        assertEquals(token.hex(), frame.copyOfRange(4, 36).hex())
    }

    @Test fun identityMustBeValidBeforeNativePathsOrCredentialUse() {
        val json = "{\"proto\":2,\"station\":\"abc-123\",\"dev\":\"AABBCCDDEEFF\",\"max_read\":65536}"
        val identity = wire.identity(json)!!
        assertEquals("aabbccddeeff", identity.device)
        assertEquals(16384, identity.maxRead)
        assertNull(wire.identity(json.replace("abc-123", "../escape")))
        assertNull(wire.identity(json.replace("AABBCCDDEEFF", "invalid")))
        assertNull(wire.identity("{}"))
    }
}

// Hex is a readable fixture notation for tests only. Production frames never pass through it.
private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private fun SyncWire.decodeHex(hex: String): SyncReply? =
    decodeFrame(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
