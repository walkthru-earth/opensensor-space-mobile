package space.opensensor.link

import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Reply waits per transport. LAN follows the firmware contract's 30 s reply wait, BLE keeps its own. */
class RequestSpecTest {
    private val requests = listOf(Request.List, Request.Close(1), Request.Open("a.parquet"), Request.Flush, Request.WifiScan, Request.GetConfig)

    @Test
    fun lanWaitsAtLeastTheContractReplyWaitForEveryFrame() {
        assertEquals(30.seconds, RequestSpec.LAN_REPLY_WAIT)
        for (request in requests) {
            val spec = RequestSpec.of(request, TransportKind.LAN)
            assertTrue(spec.firstFrameTimeout >= RequestSpec.LAN_REPLY_WAIT, "$spec first frame")
            assertTrue(spec.interFrameTimeout >= RequestSpec.LAN_REPLY_WAIT, "$spec inter frame")
        }
        assertEquals(RequestSpec.SLOW_FIRST_FRAME, RequestSpec.of(Request.Flush, TransportKind.LAN).firstFrameTimeout)
    }

    @Test
    fun bleKeepsItsOwnWaits() {
        assertEquals(RequestSpec.DEFAULT_FIRST_FRAME, RequestSpec.of(Request.List, TransportKind.BLE).firstFrameTimeout)
        assertEquals(RequestSpec.INTER_FRAME, RequestSpec.of(Request.List, TransportKind.BLE).interFrameTimeout)
        assertEquals(RequestSpec.SCAN_FIRST_FRAME, RequestSpec.of(Request.WifiScan, TransportKind.BLE).firstFrameTimeout)
        assertEquals(RequestSpec.SLOW_FIRST_FRAME, RequestSpec.of(Request.Open("a.parquet"), TransportKind.BLE).firstFrameTimeout)
    }
}
