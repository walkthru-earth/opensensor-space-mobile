package space.opensensor.archive

/**
 * Where a manifest entry sits in the device's Hive tree.
 *
 * Names are the relative paths the firmware prints:
 * `thing=…/year=…/month=…/day=…/data_HHMM_<boot>_<first>-<last>-<n>.parquet`,
 * `thing=…/unsynced/boot=…/…`, `benchmarks/boot=…/…`, or `legacy-parquet/…`.
 */
sealed interface HivePartition {
    /** Rows with UTC: `thing=…/year=YYYY/month=MM/day=DD/`. */
    data class Dated(val thing: String, val year: Int, val month: Int, val day: Int) : HivePartition {
        val isoDate: String get() = "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"
    }

    /** Rows sampled before the clock was set: `thing=…/unsynced/boot=<id>/`. */
    data class Unsynced(val thing: String, val boot: String) : HivePartition

    /** `benchmarks/boot=<id>/…` */
    data class Benchmarks(val boot: String?) : HivePartition

    /** `legacy-parquet/…` */
    data object Legacy : HivePartition

    /** Anything that does not match the documented shapes; still synced verbatim. */
    data class Other(val directory: String) : HivePartition

    companion object {
        private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

        private val DATED = Regex("""^thing=([^/]+)/year=(\d{4})/month=(\d{1,2})/day=(\d{1,2})/[^/]+$""")
        private val UNSYNCED = Regex("""^thing=([^/]+)/unsynced/boot=([^/]+)/[^/]+$""")
        private val BENCH = Regex("""^benchmarks/(?:boot=([^/]+)/)?[^/]+$""")

        fun of(name: String): HivePartition {
            DATED.matchEntire(name)?.let {
                val (thing, y, m, d) = it.destructured
                return Dated(thing, y.toInt(), m.toInt(), d.toInt())
            }
            UNSYNCED.matchEntire(name)?.let {
                val (thing, boot) = it.destructured
                return Unsynced(thing, boot)
            }
            BENCH.matchEntire(name)?.let { return Benchmarks(it.groupValues[1].ifEmpty { null }) }
            if (name.startsWith("legacy-parquet/")) return Legacy
            return Other(name.substringBeforeLast('/', missingDelimiterValue = ""))
        }
    }
}
