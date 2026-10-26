package space.opensensor.data.lan

import space.opensensor.data.ble.TransportException

/** Why a LAN connection attempt failed, so the repository can explain the fallback to Bluetooth. */
enum class LanFailure(val label: String) {
    UNREACHABLE("device not reachable on this network"),
    TIMEOUT("device did not answer the handshake in time"),
    AUTH_FAILED("token rejected, fetch it again over Bluetooth"),
    BUSY("another client is already connected"),
    PROTOCOL("unexpected handshake answer"),
}

class LanConnectException(val failure: LanFailure, detail: String? = null, cause: Throwable? = null) : TransportException(if (detail == null) failure.label else "${failure.label} ($detail)", cause = cause)
