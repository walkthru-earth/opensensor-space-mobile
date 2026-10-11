package earth.walkthru.aqsync.data.ble

/**
 * Spots a stale bond on Android without `ACTION_KEY_MISSING` (below API 36). When a sensor's bonds
 * were wiped, the phone still reports BOND_BONDED, encrypts with the old key and the sensor closes
 * the link. Those stacks only show a peer close (19), a local close (22), 133 or a bare disconnect
 * while the link opens, which reads like an ordinary radio loss.
 *
 * Counts such closes per address. A Ready link, a bond state change, any other open failure or a
 * failure while the phone holds no bond clears the count. At [threshold] consecutive ones the open
 * is a missing bond, so retries stop and the user is told to pair again. Nothing here unbonds,
 * that needs the user.
 * Pure and in memory, one instance per process.
 */
internal class BleStaleBondDetector(private val enabled: Boolean, private val threshold: Int = 2) {
    private val failures = HashMap<String, Int>()

    /**
     * An open of [address] failed before Ready. [linkStatus] is the disconnect status of a link
     * that had connected, null when it never connected or did not close (a timeout, a refused
     * operation). True when this failure should end as a missing bond.
     */
    @Synchronized
    fun onOpenFailed(address: String, bonded: Boolean, linkStatus: Int?): Boolean {
        if (!enabled) return false
        if (!bonded || linkStatus !in STALE_BOND_CLOSES) {
            failures.remove(address)
            return false
        }
        val count = (failures[address] ?: 0) + 1
        failures[address] = count
        return count >= threshold
    }

    @Synchronized
    fun onReady(address: String) {
        failures.remove(address)
    }

    @Synchronized
    fun onBondStateChanged(address: String) {
        failures.remove(address)
    }

    companion object {
        /** API 36 reports the same case as `ACTION_KEY_MISSING`, so the detector stays off there. */
        const val KEY_MISSING_ACTION_API = 36

        private val STALE_BOND_CLOSES = setOf(
            BleDisconnectCauses.PEER_USER,
            BleDisconnectCauses.LOCAL_HOST,
            BleLinkDriver.GATT_ERROR_133,
            BleLinkDriver.GATT_DISCONNECTED,
        )
    }
}
