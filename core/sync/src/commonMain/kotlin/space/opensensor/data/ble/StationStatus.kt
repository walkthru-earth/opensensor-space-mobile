package space.opensensor.data.ble

/**
 * The one link, owner and busy status that Today, Device, Files and Stations read on both
 * platforms. [busy] means a sync runs or background work holds the device. A busy device takes no
 * command that needs the link, so Flush, device settings and a second Sync all wait.
 */
data class StationStatus(
    val connection: ConnectionState,
    val reconnecting: Boolean = false,
    val syncRunning: Boolean = false,
    val heldInBackground: Boolean = false,
) {
    val busy: Boolean get() = syncRunning || heldInBackground

    /** Ready and not busy. Gates every command that needs the link. */
    val canCommand: Boolean get() = connection.isReady && !busy

    /** The banner shows while the link is not ready, or while background work holds the device. */
    val showsBanner: Boolean get() = heldInBackground || !connection.isReady

    /** Reconnect is offered only for a closed or failed link that nothing is already bringing back. */
    val offersReconnect: Boolean get() =
        !heldInBackground && !reconnecting && (connection is ConnectionState.Failed || connection is ConnectionState.Disconnected)
}
