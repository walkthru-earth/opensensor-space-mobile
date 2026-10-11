package earth.walkthru.aqsync.data.ble

import java.util.concurrent.atomic.AtomicInteger

/** Preserve the first terminal callback while connect stages and teardown run on other threads. */
internal class BleConnectionFailure {
    private val firstStatus = AtomicInteger(NO_FAILURE)

    fun reset() { firstStatus.set(NO_FAILURE) }

    fun recordDisconnected(status: Int): Int {
        firstStatus.compareAndSet(NO_FAILURE, disconnectedResult(status))
        return firstStatus.get()
    }

    fun exceptionOrNull(): TransportException? = firstStatus.get().takeIf { it != NO_FAILURE }?.let { TransportException(message(it), it) }

    fun throwIfFailed() { exceptionOrNull()?.let { throw it } }

    companion object {
        private const val NO_FAILURE = Int.MIN_VALUE
        const val PAIRING_REPAIR_MESSAGE = "Bluetooth pairing needs repair. If this sensor was reset or reflashed, forget it in Android Bluetooth settings and pair again. Your archived files are kept."

        /** Status 0 means success only for a CONNECTED callback, never a disconnected one. */
        fun disconnectedResult(status: Int): Int = if (status == 0) -1 else status

        fun message(status: Int): String = when (status) {
            in REPAIR_STATUSES -> PAIRING_REPAIR_MESSAGE
            -1 -> "Bluetooth link disconnected"
            else -> "link lost (GATT status $status)"
        }

        /** GATT 5 and 15, HCI 0x06 and 0x3D, and our key missing marker. Only pairing again helps. */
        private val REPAIR_STATUSES = setOf(
            BleDisconnectCauses.INSUFFICIENT_AUTHENTICATION,
            BleDisconnectCauses.INSUFFICIENT_ENCRYPTION,
            BleDisconnectCauses.HCI_KEY_MISSING,
            BleDisconnectCauses.HCI_MIC_FAILURE,
            BleDisconnectCauses.KEY_MISSING,
        )

        fun isRepairStatus(status: Int?): Boolean = status in REPAIR_STATUSES

        fun needsPairingRepair(state: ConnectionState): Boolean = state is ConnectionState.Failed &&
            (isRepairStatus(state.gattStatus) || state.reason.startsWith("Bluetooth pairing needs repair."))
    }
}
