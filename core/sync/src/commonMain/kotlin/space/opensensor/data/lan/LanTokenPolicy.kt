package space.opensensor.data.lan

import space.opensensor.data.ble.TransportKind
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.sync.SyncLock

/**
 * When to fetch (or re-fetch) the LAN bearer token, as pure decisions so they can be unit-tested.
 *
 * The contract makes `GET_TOKEN` Bluetooth-only. The token is fetched once when a BLE session
 * reaches Ready and none is stored. Seen on the real phone (2026-09): after another client rotated
 * the device's token, the LAN handshake failed with `ERROR` 14 (`AUTH_FAILED`), the app fell back to
 * Bluetooth as designed — and then never fetched a new token, because one was still stored. So an
 * `AUTH_FAILED` handshake **invalidates** the stored token (the repository clears the Room column),
 * which makes the next BLE session fetch again. Every fetch is quiet, on Android and iOS alike.
 * The stale mark only lets a Wi-Fi-only request fail fast until that BLE session runs.
 */
class LanTokenPolicy {
    private val staleAddresses = HashSet<String>()
    private val lock = SyncLock()

    /** True when a LAN connect failure means the stored token must be dropped. */
    fun invalidatesToken(failure: LanFailure): Boolean = failure == LanFailure.AUTH_FAILED

    /**
     * True when a LAN link that ended for [cause] proves the stored token is no longer valid: the
     * device refused it, or rotated it (BYE reason 10). The next bonded BLE session fetches again.
     */
    fun invalidatesToken(cause: DisconnectCause): Boolean =
        cause is DisconnectCause.Auth && (cause.kind == AuthKind.TOKEN_REJECTED || cause.kind == AuthKind.TOKEN_ROTATED)

    /** Record that [address]'s token was rejected; call after clearing it from storage. */
    fun markStale(address: String) {
        lock.withLock { staleAddresses += address }
    }

    fun isStale(address: String): Boolean = lock.withLock { address in staleAddresses }

    /**
     * Whether a session that just reached Ready should issue `GET_TOKEN`: only over a pipe that
     * allows it (never LAN), only for protocol v2 devices, and only when nothing usable is stored.
     */
    fun shouldFetch(kind: TransportKind, proto: Int?, storedToken: String?): Boolean =
        kind != TransportKind.LAN && (proto ?: 1) >= 2 && storedToken == null

    /** A token was fetched and stored for [address], so it is no longer stale. */
    fun onFetched(address: String) {
        lock.withLock { staleAddresses.remove(address) }
    }

    /** The user forgot the device (or rotated the token deliberately); nothing to announce later. */
    fun clear(address: String) {
        lock.withLock { staleAddresses -= address }
    }
}
