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
            5, 15, BleDisconnectCauses.KEY_MISSING -> PAIRING_REPAIR_MESSAGE
            -1 -> "Bluetooth link disconnected"
            else -> "link lost (GATT status $status)"
        }

        fun needsPairingRepair(state: ConnectionState): Boolean = state is ConnectionState.Failed &&
            (state.gattStatus == 5 || state.gattStatus == 15 || state.gattStatus == BleDisconnectCauses.KEY_MISSING || state.reason.startsWith("Bluetooth pairing needs repair."))
    }
}
