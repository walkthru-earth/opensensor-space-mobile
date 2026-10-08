package earth.walkthru.opensensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncWireTest {
    private val wire = SyncWire()

    @Test fun requestsUseCanonicalLittleEndianCodec() {
        assertEquals("0d", wire.tokenRequest())
        assertEquals("01", wire.listRequest())
        assertEquals("07", wire.statusRequest())
        assertEquals("0334120403020110000000", wire.readRequest(0x1234, 0x01020304, 16))
        assertEquals("043412", wire.closeRequest(0x1234))
        assertEquals("010001", wire.envelope(wire.listRequest()))
    }

    @Test fun strictDecodeAndSecretRedaction() {
        val token = wire.decode("46" + "1eba" + "ab".repeat(32))!!
        assertEquals("token", token.kind)
        assertEquals(47646, token.port)
        assertEquals("ab".repeat(32), token.dataHex)
        assertFalse(token.toString().contains("abab"))
        assertNull(wire.decode("461e"))
        assertNull(wire.decode("21"))
        assertNull(wire.decode("zz"))
        val chunk = wire.decode("21341204030201010203")!!
        assertEquals("chunk", chunk.kind)
        assertEquals(0x1234, chunk.handle)
        assertEquals(0x01020304, chunk.offset)
        assertEquals("010203", chunk.dataHex)
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
