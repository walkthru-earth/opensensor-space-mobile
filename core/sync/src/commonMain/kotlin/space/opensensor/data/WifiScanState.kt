package space.opensensor.data

import space.opensensor.data.protocol.Frame

/** Result of the last `WIFI_SCAN`, for the Device screen on both apps. */
sealed interface WifiScanState {
    data object Idle : WifiScanState
    data object Scanning : WifiScanState
    data class Done(val accessPoints: List<Frame.WifiAp>, val atEpochMs: Long) : WifiScanState

    /** [message] is technical detail for logs. The UI shows copy chosen by [unavailable]. */
    data class Failed(val message: String, val unavailable: Boolean) : WifiScanState
}
