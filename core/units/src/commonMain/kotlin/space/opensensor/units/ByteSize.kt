package space.opensensor.units

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** The unit a byte count prints in. Each screen maps it to its localized word, `KB` or `كيلوبايت`. */
enum class ByteUnit(internal val decimals: Int) { B(0), KB(1), MB(1), GB(2) }

/** A byte count split into its printed number and unit. */
data class ByteSize(val number: String, val unit: ByteUnit)

/**
 * [bytes] in steps of 1024, like the Settings storage card, with one decimal for KB and MB and two
 * for GB. The unit is chosen after rounding, so 1048575 B reads `1.0 MB`, never `1024.0 KB`. Files,
 * the Android sync notifications and messages print through this, and Today can reuse it.
 */
fun byteSize(bytes: Long): ByteSize = scaled(bytes.toDouble(), ByteUnit.GB)

/**
 * A transfer rate in bytes per second, the same steps as [byteSize] up to MB, so 1023.6 B/s reads
 * `1.0 KB` per second. The caller adds its per second word.
 */
fun byteRate(bytesPerSecond: Double): ByteSize = scaled(bytesPerSecond, ByteUnit.MB)

private fun scaled(value: Double, largest: ByteUnit): ByteSize {
    if (!value.isFinite()) return ByteSize(value.toString(), ByteUnit.B)
    var v = value
    var unit = ByteUnit.B
    while (unit != largest && rounded(v, unit.decimals) >= STEP) {
        v /= STEP
        unit = ByteUnit.entries[unit.ordinal + 1]
    }
    return ByteSize(formatFixed(v, unit.decimals), unit)
}

private fun rounded(v: Double, decimals: Int): Double {
    val scale = 10.0.pow(decimals)
    return (abs(v) * scale).roundToLong() / scale
}

private const val STEP = 1024.0
