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
        KEY_MISSING -> DisconnectCause.Auth(AuthKind.BOND_MISSING)
        else -> DisconnectCause.LocalAbort(gattStatus = status)
    }

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
