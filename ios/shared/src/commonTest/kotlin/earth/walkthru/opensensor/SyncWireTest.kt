package earth.walkthru.opensensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncWireTest {
    private val wire = SyncWire()

    @Test fun requestsUseCanonicalLittleEndianCodec() {
        assertEquals("0d", wire.tokenFrame().hex())
        assertEquals("07", wire.statusFrame().hex())
        assertEquals("010007", wire.envelopeFrame(wire.statusFrame()).hex())
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

    @Test fun stationIdAllowsDotButNoPathTraversal() {
        val json = "{\"proto\":2,\"station\":\"STATION\",\"dev\":\"AABBCCDDEEFF\"}"
        assertEquals("aq.lab_1-b", wire.identity(json.replace("STATION", "aq.lab_1-b"))!!.station)
        for (unsafe in listOf(".", "..", "a/b", "../x", "a\\\\b", "", "a b")) {
            assertNull(wire.identity(json.replace("STATION", unsafe)), unsafe)
        }
    }

    @Test fun missingMaxReadUsesAndroidDefaultAndNeverExceedsAdvertised() {
        val json = "{\"proto\":2,\"station\":\"s\",\"dev\":\"AABBCCDDEEFF\"}"
        assertEquals(SyncWire.DEFAULT_MAX_READ, wire.identity(json)!!.maxRead)
        assertEquals(4096, SyncWire.DEFAULT_MAX_READ)
        assertEquals(2048, wire.identity(json.replace("}", ",\"max_read\":2048}"))!!.maxRead)
        assertNull(wire.identity(json.replace("}", ",\"max_read\":0}")))
    }

    @Test fun errorFrameCarriesTheRequestOpcode() {
        val error = wire.decodeHex("7f" + "02" + "04" + "6f70656e")!!
        assertEquals("error", error.kind)
        assertEquals(2, error.op)
        assertEquals(4, error.code)
    }
}

// Hex is a readable fixture notation for tests only. Production frames never pass through it.
private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private fun SyncWire.decodeHex(hex: String): SyncReply? =
    decodeFrame(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
