package space.opensensor.files

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import space.opensensor.files.resources.Res
import space.opensensor.files.resources.unit_bytes
import space.opensensor.files.resources.unit_bytes_per_second
import space.opensensor.files.resources.unit_gigabytes
import space.opensensor.files.resources.unit_kilobytes
import space.opensensor.files.resources.unit_kilobytes_per_second
import space.opensensor.files.resources.unit_megabytes
import space.opensensor.units.formatFixed
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Instant

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
internal const val NO_VALUE = "n/a"

/** The unit a byte count prints in, each with its localized word in the catalogue. */
internal enum class ByteUnit { B, KB, MB, GB }

/** A byte count split into its number and unit, steps of 1024 like the Settings storage card. */
internal data class ByteSize(val number: String, val unit: ByteUnit)

internal fun byteSize(bytes: Long): ByteSize = when {
    bytes < 1024 -> ByteSize(bytes.toString(), ByteUnit.B)
    bytes < 1024 * 1024 -> ByteSize(formatFixed(bytes / 1024.0, 1), ByteUnit.KB)
    bytes < 1024L * 1024 * 1024 -> ByteSize(formatFixed(bytes / 1048576.0, 1), ByteUnit.MB)
    else -> ByteSize(formatFixed(bytes / 1073741824.0, 2), ByteUnit.GB)
}

/** A byte count in plain words, `1.5 KB` or `1.5 كيلوبايت`. */
@Composable
internal fun formatBytes(bytes: Long): String {
    val size = byteSize(bytes)
    return stringResource(
        when (size.unit) {
            ByteUnit.B -> Res.string.unit_bytes
            ByteUnit.KB -> Res.string.unit_kilobytes
            ByteUnit.MB -> Res.string.unit_megabytes
            ByteUnit.GB -> Res.string.unit_gigabytes
        },
        size.number,
    )
}

@Composable
internal fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond < 1024 -> stringResource(Res.string.unit_bytes_per_second, formatFixed(bytesPerSecond, 0))
    else -> stringResource(Res.string.unit_kilobytes_per_second, formatFixed(bytesPerSecond / 1024.0, 1))
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
