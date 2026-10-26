package space.opensensor.data

/**
 * Continuity checks after reading INFO, before persisting identity or collecting files.
 * Addresses and hardware IDs correlate an enrolled peer; neither is a cryptographic fingerprint.
 * [authenticatedBle] means the caller has completed the enrolled/owner-approved BLE connection.
 * A LAN HELLO and discovery records cannot authorize enrollment or a station-generation change.
 * Boot, firmware and dictionary changes are deliberately not identity inputs.
 */
object DeviceContinuityPolicy {
    data class Identity(val address: String, val deviceId: String?, val station: String?)

    sealed interface Decision {
        data class Accept(val stationChanged: Boolean = false, val invalidateLanToken: Boolean = false) : Decision
        data class Reject(val reason: Reason) : Decision
    }

    /** Mapped to copy in the UI (`DeviceMessages.kt`). */
    enum class Reason {
        ENDPOINT_CHANGED,
        HARDWARE_CHANGED,
        HARDWARE_MISSING,
        HARDWARE_MALFORMED,
        STATION_MISSING,
        STATION_MALFORMED,
        ENROLLMENT_REQUIRES_BLE,
        STATION_CHANGE_REQUIRES_BLE,
        STATION_CHANGE_REQUIRES_HARDWARE,
    }

    fun evaluate(expected: Identity?, observed: Identity, authenticatedBle: Boolean): Decision {
        if (observed.station.isNullOrBlank()) return Decision.Reject(Reason.STATION_MISSING)
        if (observed.station in setOf(".", "..") || !stationId.matches(observed.station)) {
            return Decision.Reject(Reason.STATION_MALFORMED)
        }
        if (expected != null && !expected.address.equals(observed.address, ignoreCase = true)) {
            return Decision.Reject(Reason.ENDPOINT_CHANGED)
        }
        if (observed.deviceId != null && !hardwareId.matches(observed.deviceId)) {
            return Decision.Reject(Reason.HARDWARE_MALFORMED)
        }
        val previousId = expected?.deviceId
        if (previousId != null) {
            if (observed.deviceId == null) return Decision.Reject(Reason.HARDWARE_MISSING)
            if (!previousId.equals(observed.deviceId, ignoreCase = true)) return Decision.Reject(Reason.HARDWARE_CHANGED)
        }
        val previousStation = expected?.station
        if (previousStation == null) {
            return if (authenticatedBle) Decision.Accept() else Decision.Reject(Reason.ENROLLMENT_REQUIRES_BLE)
        }
        if (previousStation == observed.station) return Decision.Accept()
        if (!authenticatedBle) return Decision.Reject(Reason.STATION_CHANGE_REQUIRES_BLE)
        if (previousId == null || !hardwareId.matches(previousId)) {
            return Decision.Reject(Reason.STATION_CHANGE_REQUIRES_HARDWARE)
        }
        return Decision.Accept(stationChanged = true, invalidateLanToken = true)
    }

    private val hardwareId = Regex("[0-9a-fA-F]{12}")
    private val stationId = Regex("[0-9A-Za-z._-]+")
}
