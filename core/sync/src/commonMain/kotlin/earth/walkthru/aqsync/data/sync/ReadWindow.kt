package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.link.LinkSession

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
 * Halving the window would have halved throughput for nothing, so both links start at the device's
 * `max_read` (16 KiB). This is the ceiling. [AdaptiveReadWindow] halves below it only after a window
 * was actually lost on that link, and the client-side window resume in `SyncEngine` stays as the
 * contract's robustness rule.
 */
object ReadWindow {
    /** Used when `info.max_read` is absent (the firmware always sends 16384; kept as a guard). */
    const val DEFAULT_MAX_READ = 4096

    /**
     * The `READ` length for [transport]: the device's `max_read` on every link (see the class note
     * for why BLE is *not* special-cased), [DEFAULT_MAX_READ] when it is missing. [transport] is
     * taken so a future per-link choice has exactly one place to go.
     */
    fun forTransport(transport: AqTransport, maxRead: Int?): Int = forKind(transport.kind, maxRead)

    /**
     * The `READ` length on a [kind] link whose session holds at most [budget] unread bytes. A window
     * above half of it would end every `READ` in `ResponseOverflow`, so it is clamped there.
     */
    fun forKind(kind: TransportKind, maxRead: Int?, budget: Long = LinkSession.defaultBudget(kind)): Int {
        val asked = maxRead?.takeIf { it > 0 } ?: DEFAULT_MAX_READ
        return asked.toLong().coerceAtMost(budget / 2).coerceAtLeast(1).toInt()
    }
}

/**
 * The `READ` length of one link, adapted to what the link delivers (#98). Additive increase,
 * multiplicative decrease, in whole `CHUNK` frames.
 *
 * - It starts at [max], the device's `max_read` through [ReadWindow.forKind], so a clean link reads
 *   exactly as before. `STATUS` carries no window field (docs/shared), so `max_read` from `INFO` or the
 *   LAN `HELLO` is the only firmware cap. When it is missing the cap is [ReadWindow.DEFAULT_MAX_READ].
 * - A lost window ([earth.walkthru.aqsync.data.ble.WindowLostException], the loss the app recovers
 *   from) halves it, rounded down to whole frames, never below [min].
 * - [GROW_AFTER] clean windows in a row grow it by one [min] step, never above [max].
 *
 * [frame] is the payload of one `CHUNK`, `payload_max` (capped at 512 on BLE, the GATT attribute
 * limit) less the 7-byte header. [min] is [MIN_FRAMES] frames, or [max] when the device allows less.
 * One instance lives per link session and is not thread safe. A link reads one file at a time.
 */
class AdaptiveReadWindow(ceiling: Int, payloadMax: Int, kind: TransportKind) {
    val frame: Int = (minOf(payloadMax, if (kind == TransportKind.BLE) BLE_FRAME_CAP else payloadMax) - CHUNK_HEADER).coerceAtLeast(1)
    val max: Int = ceiling.coerceAtLeast(1)
    val min: Int = minOf(MIN_FRAMES * frame, max)

    /** The length of the next `READ`. */
    var current: Int = max
        private set

    private var clean = 0

    /** A window arrived whole. */
    fun onClean() {
        if (current >= max) return
        if (++clean < GROW_AFTER) return
        clean = 0
        current = minOf(max, current + min)
    }

    /** A window lost frames and is read again. */
    fun onLost() {
        clean = 0
        current = maxOf(min, current / 2 / frame * frame)
    }

    companion object {
        /** The floor, in frames. Below it the per-`READ` round trip costs more than a loss. */
        const val MIN_FRAMES = 4

        /** Clean windows in a row before the window grows one step. */
        const val GROW_AFTER = 8

        /** `CHUNK` header bytes, type u8, handle u16, offset u32 (docs/shared). */
        const val CHUNK_HEADER = 7

        /** The GATT attribute-value limit the firmware caps BLE frames at. */
        const val BLE_FRAME_CAP = 512

        fun of(kind: TransportKind, payloadMax: Int, maxRead: Int?, budget: Long = LinkSession.defaultBudget(kind)): AdaptiveReadWindow =
            AdaptiveReadWindow(ReadWindow.forKind(kind, maxRead, budget), payloadMax, kind)
    }
}
