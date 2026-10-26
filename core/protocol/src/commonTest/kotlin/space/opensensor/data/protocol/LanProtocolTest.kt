package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** The `_ossync._tcp` names of `ble-sync-protocol.md`, which firmware advertises and both phones read. */
class LanProtocolTest {
    @Test
    fun theServiceAndTxtKeysMatchTheWireContract() {
        assertEquals("_ossync._tcp", LanProtocol.SERVICE_TYPE)
        assertEquals("proto", LanProtocol.TXT_PROTO)
        assertEquals("thing", LanProtocol.TXT_THING)
        assertEquals("dev", LanProtocol.TXT_DEV)
        assertEquals("fw", LanProtocol.TXT_FW)
    }
}
