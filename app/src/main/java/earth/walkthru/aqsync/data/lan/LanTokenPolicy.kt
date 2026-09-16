package earth.walkthru.aqsync.data.lan

import earth.walkthru.aqsync.data.ble.TransportKind

/**
 * When to fetch (or re-fetch) the LAN bearer token, as pure decisions so they can be unit-tested.
 *
 * The contract makes `GET_TOKEN` Bluetooth-only. The token is fetched once when a BLE session
 * reaches Ready and none is stored. Seen on the real phone (2026-09): after another client rotated
 * the device's token, the LAN handshake failed with `ERROR` 14 (`AUTH_FAILED`), the app fell back to
 * Bluetooth as designed — and then never fetched a new token, because one was still stored. So an
 * `AUTH_FAILED` handshake **invalidates** the stored token (the repository clears the Room column),
 * which makes the next BLE session fetch again; the renewal is remembered here so the UI can say
 * "LAN token renewed" exactly once instead of the silent first-time fetch.
 */
class LanTokenPolicy {
    private val staleAddresses = HashSet<String>()

    /** True when a LAN connect failure means the stored token must be dropped. */
    fun invalidatesToken(failure: LanFailure): Boolean = failure == LanFailure.AUTH_FAILED

    /** Record that [address]'s token was rejected; call after clearing it from storage. */
    fun markStale(address: String) {
        synchronized(staleAddresses) { staleAddresses += address }
    }

    fun isStale(address: String): Boolean = synchronized(staleAddresses) { address in staleAddresses }

    /**
     * Whether a session that just reached Ready should issue `GET_TOKEN`: only over a pipe that
     * allows it (never LAN), only for protocol v2 devices, and only when nothing usable is stored.
     */
    fun shouldFetch(kind: TransportKind, proto: Int?, storedToken: String?): Boolean =
        kind != TransportKind.LAN && (proto ?: 1) >= 2 && storedToken == null

    /**
     * A token was fetched and stored for [address]. Returns true when this was a renewal after an
     * `AUTH_FAILED` (so the UI should announce it), and forgets the stale mark either way.
     */
    fun onFetched(address: String): Boolean = synchronized(staleAddresses) { staleAddresses.remove(address) }

    /** The user forgot the device (or rotated the token deliberately); nothing to announce later. */
    fun clear(address: String) {
        synchronized(staleAddresses) { staleAddresses -= address }
    }
}
