package space.opensensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncWireTest {
    private val wire = SyncWire()

    @Test fun clockIdentityRetainsTheFullBootValue() {
        val identity = wire.identity("""{"proto":3,"station":"abc-123","dev":"AABBCCDDEEFF","boot":"b41d37a48c04031e9397e28ce48c2f43"}""")!!
        assertEquals("b41d37a48c04031e9397e28ce48c2f43", identity.boot)
    }

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

    @Test fun validCommandRepliesReachTheLanEngine() {
        // LanSession validates each body before routing the original bytes to SensorSync.
        assertEquals("frame", wire.decodeHex("306bdaaa6a00000000e1ac03110000000040e20100")?.kind)
        assertEquals("frame", wire.decodeHex("31020002000000")?.kind)
    }

    @Test fun handshakeCarriesOnlyTheRawToken() {
        val token = ByteArray(32) { it.toByte() }
        val frame = wire.handshakeFrame(token)
        assertEquals(36, frame.size)
        assertEquals(token.hex(), frame.copyOfRange(4, 36).hex())
    }

    @Test fun identityMustBeValidBeforeNativePathsOrCredentialUse() {
        val json = "{\"proto\":3,\"station\":\"abc-123\",\"dev\":\"AABBCCDDEEFF\",\"max_read\":65536}"
        val identity = wire.identity(json)!!
        assertEquals("aabbccddeeff", identity.device)
        assertEquals(16384, identity.maxRead)
        assertNull(wire.identity(json.replace("abc-123", "../escape")))
        assertNull(wire.identity(json.replace("AABBCCDDEEFF", "invalid")))
        assertNull(wire.identity("{}"))
        assertNull(wire.identity(json.replace("\"proto\":3", "\"proto\":2")), "proto 2 needs a firmware update")
    }

    @Test fun stationIdAllowsDotButNoPathTraversal() {
        val json = "{\"proto\":3,\"station\":\"STATION\",\"dev\":\"AABBCCDDEEFF\"}"
        assertEquals("aq.lab_1-b", wire.identity(json.replace("STATION", "aq.lab_1-b"))!!.station)
        for (unsafe in listOf(".", "..", "a/b", "../x", "a\\\\b", "", "a b")) {
            assertNull(wire.identity(json.replace("STATION", unsafe)), unsafe)
        }
    }

    @Test fun missingMaxReadUsesAndroidDefaultAndNeverExceedsAdvertised() {
        val json = "{\"proto\":3,\"station\":\"s\",\"dev\":\"AABBCCDDEEFF\"}"
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

    /** Android TcpLinkDriver parity (ad7a747a). Only ERROR 14 or BYE auth-failed rejects the token. */
    @Test fun handshakeRefusalRejectsTheTokenOnlyOnAnAuthVerdict() {
        assertEquals(HandshakeRefusal.NONE, wire.decodeHex("47" + "03" + "0002" + "7b7d")!!.refusal)
        assertEquals(HandshakeRefusal.TOKEN_REJECTED, wire.decodeHex("7f" + "00" + "0e")!!.refusal)
        assertEquals(HandshakeRefusal.BUSY, wire.decodeHex("7f" + "00" + "07")!!.refusal)
        assertEquals(HandshakeRefusal.INVALID, wire.decodeHex("7f" + "00" + "04")!!.refusal)
        val authBye = wire.decodeHex("4b" + "03" + "04030201f5ff0300100e0000")!!
        assertEquals("bye", authBye.kind)
        assertEquals(HandshakeRefusal.TOKEN_REJECTED, authBye.refusal)
        // A rotated token is no longer valid either, and a full session table is busy.
        assertEquals(HandshakeRefusal.TOKEN_REJECTED, wire.decodeHex("4b" + "0a" + "04030201f5ff0300100e0000")!!.refusal)
        assertEquals(HandshakeRefusal.BUSY, wire.decodeHex("4b" + "04" + "04030201f5ff0300100e0000")!!.refusal)
        // A reboot or Wi-Fi drop says goodbye without an auth verdict, so the token stays.
        assertEquals(HandshakeRefusal.PEER_CLOSED, wire.decodeHex("4b" + "0b" + "04030201f5ff0300100e0000")!!.refusal)
        assertEquals(HandshakeRefusal.PEER_CLOSED, wire.decodeHex("4b" + "08" + "04030201f5ff0300100e0000")!!.refusal)
    }
}

// Hex is a readable fixture notation for tests only. Production frames never pass through it.
private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private fun SyncWire.decodeHex(hex: String): SyncReply? =
    decodeFrame(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
