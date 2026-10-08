package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord

/** The plain record [TimeEstimate] works with. */
fun ArchiveRecord.info(): IndexedFileInfo = IndexedFileInfo(name, bootId, purpose, indexed, rows, utcRows, firstUtcNs, lastUtcNs, firstMonoUs, lastMonoUs)

/** UTC station-days, the unit the tiers and the derived daily files are built in. */
object StationDays {
    const val DAY_NS: Long = 86_400L * 1_000_000_000L

    /** The UTC epoch day of an instant in nanoseconds, rounding down before 1970 too. */
    fun dayOf(ns: Long): Long = ns.floorDiv(DAY_NS)

    /**
     * Which station-days a set of indexed telemetry files touches, by real UTC or by the estimated span
     * (§4.6). Files not indexed, of another purpose, or without a place on the timeline touch none.
     */
    fun of(records: List<ArchiveRecord>): Map<String, Set<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        for (f in records) {
            if (!f.indexed || !f.isTelemetry) continue
            val first = f.timelineFirstNs ?: continue
            val last = f.timelineLastNs ?: continue
            val days = out.getOrPut(f.station) { HashSet() }
            var d = dayOf(first)
            val lastDay = dayOf(last)
            while (d <= lastDay) { days += d; d++ }
        }
        return out
    }
}
