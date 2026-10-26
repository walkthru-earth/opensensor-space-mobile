package space.opensensor.devices

/** The unit an "N ago" text uses. */
enum class AgoUnit { JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }

/** Pure text helpers, so the leaf `TickingText` lambdas format without a composable call. */
object DeviceText {
    /** The unit and count of an age, the same steps as the Android `formatAgo`. */
    fun ago(ageMs: Long): Pair<AgoUnit, Long> {
        val s = ageMs / 1000
        return when {
            ageMs < 0 -> AgoUnit.JUST_NOW to 0L
            s < 60 -> AgoUnit.SECONDS to s
            s < 3600 -> AgoUnit.MINUTES to s / 60
            s < 86_400 -> AgoUnit.HOURS to s / 3600
            else -> AgoUnit.DAYS to s / 86_400
        }
    }

    /** Puts [args] into a resource template's positional `%1$d` or `%1$s` slots. */
    fun fill(template: String, vararg args: Any): String {
        var out = template
        args.forEachIndexed { i, arg ->
            val n = i + 1
            out = out.replace("%$n\$d", arg.toString()).replace("%$n\$s", arg.toString())
        }
        return out
    }

    /** The UTC day of month and month number (1 to 12) of [epochS]. Civil calendar, no time zone data. */
    fun utcDayMonth(epochS: Long): Pair<Int, Int> {
        val days = floorDiv(epochS, 86_400L)
        // Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return day to month
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }
}
