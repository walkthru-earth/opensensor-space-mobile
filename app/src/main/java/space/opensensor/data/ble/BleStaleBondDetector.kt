package space.opensensor.data.ble

/**
 * Spots a stale bond on Android without `ACTION_KEY_MISSING` (below API 36). When a sensor's bonds
 * were wiped, the phone still reports BOND_BONDED, encrypts with the old key and the sensor closes
 * the link. Our firmware closes it on purpose (HCI 0x13, status 19) and a stack may close it
 * itself (22), which reads like an ordinary radio loss. 133 and a bare disconnect are not counted,
 * flaky stacks report them during discovery and MTU with a healthy bond, and no stale bond on a
 * phone has shown them.
 *
 * Counts such closes per address within [windowMs]. A Ready link, a bond state change, a link to
 * a device the phone holds no bond for, any other open failure or a failure while the phone holds
 * no bond clears the count. At [threshold] consecutive closes after the app's own encryption point
 * (the bond check and the CCCD writes) the open is a missing bond, so retries stop and the user is
 * told to pair again, and the count starts over. Android may encrypt a bonded link right after
 * connect, so closes before that point still count, but only toward a [Verdict.HINT], which shows
 * the repair hint and keeps the normal retries. Nothing here unbonds, that needs the user.
 * Pure and in memory, one instance per process. [now] is a monotonic clock in milliseconds.
 */
internal class BleStaleBondDetector(
    private val enabled: Boolean,
    private val threshold: Int = 2,
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** What a failed open says about the bond. */
    enum class Verdict {
        /** Nothing points at the keys. */
        NONE,

        /** The keys may be stale. Show the repair hint, keep the normal retries. */
        HINT,

        /** The keys are stale. End as a missing bond, retries stop. */
        STALE,
    }

    private class Close(val at: Long, val afterEncryption: Boolean)

    /** The consecutive stale bond closes per address, oldest first. */
    private val failures = HashMap<String, ArrayDeque<Close>>()

    /**
     * An open of [address] failed before Ready. [linkStatus] is the disconnect status of a link
     * that had connected, null when it never connected or did not close (a timeout, a refused
     * operation). [afterEncryption] is true once the open reached the bond check.
     */
    @Synchronized
    fun onOpenFailed(address: String, bonded: Boolean, linkStatus: Int?, afterEncryption: Boolean): Verdict {
        if (!enabled) return Verdict.NONE
        if (!bonded || linkStatus !in STALE_BOND_CLOSES) {
            failures.remove(address)
            return Verdict.NONE
        }
        val at = now()
        val closes = failures.getOrPut(address) { ArrayDeque() }
        // Closes hours apart are radio losses, not one stale bond.
        while (closes.isNotEmpty() && at - closes.first().at > windowMs) closes.removeFirst()
        closes.addLast(Close(at, afterEncryption))
        if (closes.count { it.afterEncryption } >= threshold) {
            failures.remove(address)
            return Verdict.STALE
        }
        return if (closes.size >= threshold) Verdict.HINT else Verdict.NONE
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

        private val STALE_BOND_CLOSES = setOf(BleDisconnectCauses.PEER_USER, BleDisconnectCauses.LOCAL_HOST)
    }
}
