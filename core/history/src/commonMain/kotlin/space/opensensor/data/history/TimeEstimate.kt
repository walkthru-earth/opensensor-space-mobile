package space.opensensor.data.history

import space.opensensor.archive.ClockAnchor
import kotlin.math.abs

/*
 * UTC for rows the logger stamped before it had a clock (§4.6 of the evaluation).
 *
 * Every row carries `monotonic_us`, microseconds since power-on. Once the same boot receives a time
 * anchor (`SET_TIME`), its later rows carry both `event_time_utc_us` (`event_time_utc_ns` on older files) and `monotonic_us`, which fixes
 * the offset between the two clocks for that boot: `utc = anchor_utc + (mono − anchor_mono)`. Applied
 * backwards, it dates the boot's earlier, unsynced rows — **as an estimate**: the ESP32 crystal drifts
 * (tens of ppm), so the uncertainty grows with the distance from the anchor and is reported with it.
 *
 * Rules, all pure:
 * - only rows of the **same boot** (`boot_id`) as the anchor — a reboot resets the monotonic clock;
 * - use the earliest boot-scoped receipt or fully-timed file, so a successful handshake can
 *   immediately date older data without waiting for another file rotation;
 * - the estimate never overwrites the event time column: it lives in its own column/fields and every
 *   UI that shows it says "estimated".
 */
/**
 * What the time estimate needs to know about an indexed file — a plain record the Room entity maps to
 * in :app (`ArchivedFile.info()`). Keeps this module free of the database.
 */
data class IndexedFileInfo(
    val name: String,
    val bootId: String?,
    val purpose: String?,
    val indexed: Boolean,
    val rows: Long?,
    val utcRows: Long?,
    val firstUtcNs: Long?,
    val lastUtcNs: Long?,
    val firstMonoUs: Long?,
    val lastMonoUs: Long?,
)

object TimeEstimate {

    /** Drift assumption for display, not a measured accuracy bound or a transport-error bound. */
    const val DRIFT_PPM = 50.0

    data class Anchor(val boot: String, val utcNs: Long, val monoUs: Long, val sourceFile: String) {
        fun estimateNs(monoUs: Long): Long = requireNotNull(estimateNsOrNull(monoUs)) { "invalid clock mapping" }

        /** Bad source timestamps must never wrap into an apparently valid UTC partition. */
        fun estimateNsOrNull(monoUs: Long): Long? {
            if (monoUs < 0 || this.monoUs < 0) return null
            val delta = monoUs - this.monoUs // subtraction of nonnegative signed values cannot overflow
            if (delta < Long.MIN_VALUE / 1000L || delta > Long.MAX_VALUE / 1000L) return null
            val nanos = delta * 1000L
            if (nanos > 0 && utcNs > Long.MAX_VALUE - nanos) return null
            if (nanos < 0 && utcNs < Long.MIN_VALUE - nanos) return null
            return utcNs + nanos
        }

        /** Uncertainty at [monoUs], in seconds, from the drift assumption alone. */
        fun uncertaintySeconds(monoUs: Long): Double = abs(monoUs - this.monoUs) / 1e6 * DRIFT_PPM / 1e6
    }

    /** One anchor per boot. Callers must scope both files and receipts to the same producer thing. */
    fun anchors(files: List<IndexedFileInfo>, receipts: List<ClockAnchor> = emptyList()): Map<String, Anchor> {
        val out = HashMap<String, Anchor>()
        for (f in files) {
            if (!f.indexed || !(f.purpose == null || f.purpose == "telemetry")) continue
            val boot = f.bootId ?: continue
            val rows = f.rows ?: continue
            if (rows == 0L || f.utcRows != rows) continue
            val utc = f.firstUtcNs ?: continue
            val mono = f.firstMonoUs ?: continue
            val candidate = Anchor(boot, utc, mono, f.name)
            val current = out[boot]
            if (current == null || candidate.monoUs < current.monoUs) out[boot] = candidate
        }
        for (receipt in receipts) {
            val candidate = Anchor(receipt.boot, receipt.utcNs, receipt.monoUs, "clock:${receipt.boot}:${receipt.monoUs}:${receipt.utcNs}")
            val current = out[candidate.boot]
            if (current == null || candidate.monoUs <= current.monoUs) out[candidate.boot] = candidate
        }
        return out
    }

    /** Estimated span for an unsynced file of a boot with an anchor, else null. */
    fun span(file: IndexedFileInfo, anchors: Map<String, Anchor>): Pair<Long, Long>? {
        if ((file.utcRows ?: 0L) > 0L) return null // has real UTC (fully or partially): not estimated
        val boot = file.bootId ?: return null
        val a = anchors[boot] ?: return null
        val first = file.firstMonoUs ?: return null
        val last = file.lastMonoUs ?: return null
        if (first > last) return null
        val start = a.estimateNsOrNull(first) ?: return null
        val end = a.estimateNsOrNull(last) ?: return null
        return start to end
    }

    /** Human label: "±3 s" / "±2 min". */
    fun uncertaintyLabel(seconds: Double): String = when {
        seconds < 1 -> "±1 s"
        seconds < 90 -> "±${seconds.toInt()} s"
        else -> "±${(seconds / 60).toInt()} min"
    }
}
