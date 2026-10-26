package space.opensensor.data

/**
 * The pure part of choosing a pipe for a connect (contract, "LAN transport"). LAN is tried only
 * when the device's thing is discovered on this network and a token is stored, else Bluetooth.
 * The caller looks the thing up in its discovery map and resolves the host; this decides the
 * rest from plain values, so both apps apply the same rule.
 */
object TransportSelector {
    /**
     * The port to try over LAN, or null when Bluetooth must be used.
     *
     * [knownDeviceId] is the pinned hardware id and [advertisedDev] the mDNS `dev` TXT value. A
     * pinned id that differs from the advertisement means another board answers under this
     * thing, so LAN is skipped. A missing or out-of-range [advertisedPort] falls back to the
     * port stored with the token.
     */
    fun lanPort(
        hasLanToken: Boolean,
        knownDeviceId: String?,
        advertisedDev: String?,
        advertisedPort: Int,
        knownPort: Int?,
    ): Int? {
        if (!hasLanToken) return null
        if (knownDeviceId != null && !knownDeviceId.equals(advertisedDev, ignoreCase = true)) return null
        return advertisedPort.takeIf { it in 1..65535 } ?: knownPort
    }
}
