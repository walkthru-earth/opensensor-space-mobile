package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.AqTransport

/**
 * How many bytes one `READ` asks for. The single place this decision lives.
 *
 * History, so nobody shrinks the BLE window again without a measurement: on 2026-09-17 a OnePlus 7
 * Pro (Android 16) saw runs of 16–18 consecutive `CHUNK`s vanish at the start of every 16 KiB
 * window (`expected 32768, got CHUNK at 41894` = 32768 + 18 × 507), and the first reading was
 * "Android's BT stack drops notification bursts; use 4 KiB windows over BLE". The root cause was
 * the firmware: v5 sent 514-byte frames (MTU 517 − 3) while the GATT attribute-value limit is 512
 * bytes; Android silently discards oversize notifications (macOS accepts them), so every *full*
 * chunk was lost and only the short last one arrived. The firmware now caps BLE frames at 512.
 * Halving the window would have halved throughput for nothing, so both links keep the device's
 * `max_read` (16 KiB); the client-side window resume in `SyncEngine` stays as the contract's
 * robustness rule.
 */
object ReadWindow {
    /** Used when `info.max_read` is absent (the firmware always sends 16384; kept as a guard). */
    const val DEFAULT_MAX_READ = 4096

    /**
     * The `READ` length for [transport]: the device's `max_read` on every link (see the class note
     * for why BLE is *not* special-cased), [DEFAULT_MAX_READ] when it is missing. [transport] is
     * taken so a future per-link choice has exactly one place to go.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forTransport(transport: AqTransport, maxRead: Int?): Int = maxRead?.takeIf { it > 0 } ?: DEFAULT_MAX_READ
}
