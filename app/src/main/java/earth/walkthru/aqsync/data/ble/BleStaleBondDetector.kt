package earth.walkthru.aqsync.data.ble

/**
 * Spots a stale bond on Android without `ACTION_KEY_MISSING` (below API 36). When a sensor's bonds
 * were wiped, the phone still reports BOND_BONDED, encrypts with the old key and the sensor closes
 * the link. Those stacks only show a peer close (19), a local close (22), 133 or a bare disconnect
 * while the link opens, which reads like an ordinary radio loss.
 *
 * Counts such closes per address within [windowMs]. A Ready link, a bond state change, a link to
 * a device the phone holds no bond for, any other open failure or a failure while the phone holds
 * no bond clears the count. At [threshold] consecutive ones the open is a missing bond, so retries
 * stop and the user is told to pair again, and the count starts over. Nothing here unbonds, that
 * needs the user.
 * Pure and in memory, one instance per process. [now] is a monotonic clock in milliseconds.
 */
internal class BleStaleBondDetector(
    private val enabled: Boolean,
    private val threshold: Int = 2,
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Times of the consecutive stale bond closes per address, oldest first. */
    private val failures = HashMap<String, ArrayDeque<Long>>()

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
        val at = now()
        val times = failures.getOrPut(address) { ArrayDeque() }
        // Closes hours apart are radio losses, not one stale bond.
        while (times.isNotEmpty() && at - times.first() > windowMs) times.removeFirst()
        times.addLast(at)
        if (times.size < threshold) return false
        failures.remove(address)
        return true
    }

    /** A link to [address] opened while the phone holds no bond for it, so no old key is in use. */
    @Synchronized
    fun onUnbondedLink(address: String) {
        failures.remove(address)
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

        /** Reconnect and collection retries land well inside this, unrelated losses mostly do not. */
        const val WINDOW_MS = 3 * 60_000L

        private val STALE_BOND_CLOSES = setOf(
            BleDisconnectCauses.PEER_USER,
            BleDisconnectCauses.LOCAL_HOST,
            BleLinkDriver.GATT_ERROR_133,
            BleLinkDriver.GATT_DISCONNECTED,
        )
    }
}
