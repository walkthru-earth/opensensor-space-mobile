package space.opensensor.files

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import space.opensensor.units.formatFixed
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Instant

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
internal const val NO_VALUE = "n/a"

/** Byte counts as the Android app always printed them, binary units with a dot separator. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> formatFixed(bytes / 1024.0, 1) + " KiB"
    bytes < 1024L * 1024 * 1024 -> formatFixed(bytes / 1048576.0, 2) + " MiB"
    else -> formatFixed(bytes / 1073741824.0, 2) + " GiB"
}

internal fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond < 1024 -> formatFixed(bytesPerSecond, 0) + " B/s"
    else -> formatFixed(bytesPerSecond / 1024.0, 1) + " KiB/s"
}

private fun pad(v: Int, width: Int = 2) = v.toString().padStart(width, '0')

/** A UTC instant in nanoseconds as `2026-09-08 19:45:10Z`. */
internal fun formatUtcNs(ns: Long): String {
    val seconds = floorDiv(ns, 1_000_000_000L)
    val t = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC)
    return "${pad(t.year, 4)}-${pad(t.month.ordinal + 1)}-${pad(t.day)} ${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}Z"
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
}

/** Uptime in microseconds as `1h 02m 03s` or `2m 03s`. */
internal fun formatMonoUs(us: Long): String {
    val s = us / 1_000_000
    return if (s >= 3600) "${s / 3600}h ${pad((s % 3600 / 60).toInt())}m ${pad((s % 60).toInt())}s" else "${s / 60}m ${pad((s % 60).toInt())}s"
}

/**
 * A decoded value for the column table. Whole numbers print as integers, anything else with four
 * significant digits, like Java's `%.4g` the Android screen used.
 */
internal fun formatValue(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    if (v == round(v) && abs(v) < 1e15) return v.toLong().toString()
    if (v == 0.0) return "0.000"
    var exp = floor(log10(abs(v))).toInt()
    var mantissa = v / 10.0.pow(exp)
    // Rounding to four digits can carry into the next power of ten, 9.9996 reads 10.00.
    if (abs(round(mantissa * 1000) / 1000) >= 10.0) {
        exp += 1
        mantissa = v / 10.0.pow(exp)
    }
    return if (exp < -4 || exp >= 4) {
        val sign = if (exp < 0) "-" else "+"
        formatFixed(mantissa, 3) + "e" + sign + pad(abs(exp))
    } else {
        formatFixed(v, 3 - exp)
    }
}
