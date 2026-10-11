package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.TimeoutPhase

/**
 * Maps Android GATT statuses to a [DisconnectCause]. Pure, so the table is tested on the host.
 * The status is the one `onConnectionStateChange` reports, or the first one [BleConnectionFailure]
 * kept while a connect was running.
 */
object BleDisconnectCauses {
    /** Our own marker for `ACTION_KEY_MISSING`. Android has no GATT status for it. */
    const val KEY_MISSING = -2

    /**
     * HCI 0x06, PIN or key missing. AOSP `hci_error_code.h` `HCI_ERR_KEY_MISSING`. The peer has no
     * key for the one this phone used, typically after the sensor's bonds were wiped.
     */
    const val HCI_KEY_MISSING = 6

    /**
     * HCI 0x3D, connection terminated due to MIC failure (Core spec Vol 1 Part F). AOSP has no name
     * for it, but `gatt_main.cc` passes the raw HCI reason through as the status. Both sides
     * encrypted with different keys, the same stale bond.
     */
    const val HCI_MIC_FAILURE = 61

    /** HCI 0x08, the supervision timeout. The radio lost the peer. */
    const val CONNECTION_TIMEOUT = 8

    /** HCI 0x13, the peer closed the link on purpose. */
    const val PEER_USER = 19

    /** HCI 0x16, this phone's host closed the link. */
    const val LOCAL_HOST = 22
    const val INSUFFICIENT_AUTHENTICATION = 5
    const val INSUFFICIENT_ENCRYPTION = 15

    fun ofStatus(status: Int): DisconnectCause = when (status) {
        PEER_USER, BleLinkDriver.GATT_DISCONNECTED -> DisconnectCause.PeerClosed()
        INSUFFICIENT_AUTHENTICATION -> DisconnectCause.Auth(AuthKind.BOND_FAILED)
        INSUFFICIENT_ENCRYPTION -> DisconnectCause.Auth(AuthKind.ENCRYPTION_FAILED)
        KEY_MISSING, HCI_KEY_MISSING, HCI_MIC_FAILURE -> DisconnectCause.Auth(AuthKind.BOND_MISSING)
        else -> DisconnectCause.LocalAbort(gattStatus = status)
    }

    /**
     * The bond keys exist but failed, or are gone. Pairing again fixes it. A pairing that was never
     * finished ([AuthKind.BOND_FAILED], for example a PIN prompt that timed out) is not one.
     */
    fun needsPairingRepair(cause: DisconnectCause?): Boolean =
        cause == DisconnectCause.Auth(AuthKind.BOND_MISSING) || cause == DisconnectCause.Auth(AuthKind.ENCRYPTION_FAILED)

    /** Why [BleLinkDriver.open] failed. Pairing failures are credentials, the rest is the link. */
    fun ofOpenFailure(e: TransportException): DisconnectCause {
        val bond = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<BleLinkDriver.BondException>().firstOrNull()
        return when {
            bond?.missing == true -> DisconnectCause.Auth(AuthKind.BOND_MISSING)
            bond != null -> DisconnectCause.Auth(AuthKind.BOND_FAILED)
            e is BleLinkDriver.ConnectTimeoutException -> DisconnectCause.Timeout(TimeoutPhase.OPEN, 0)
            else -> e.gattStatus?.let(::ofStatus) ?: DisconnectCause.LocalAbort()
        }
    }
}
