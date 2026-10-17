package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveRecord

/** The plain record [TimeEstimate] works with. */
fun ArchiveRecord.info(): IndexedFileInfo = IndexedFileInfo(name, bootId, purpose, isIndexed, rows, utcRows, firstUtcNs, lastUtcNs, firstMonoUs, lastMonoUs)

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
            if (!f.isIndexed || !f.isTelemetry) continue
            val first = f.timelineFirstNs ?: continue
            val last = f.timelineLastNs ?: continue
            val days = out.getOrPut(f.station) { HashSet() }
            var d = dayOf(first)
            val lastDay = dayOf(last)
            while (d <= lastDay) { days += d; d++ }
        }
        return out
    }

    /**
     * The sources of every day of one station's [items], from one listing instead of a query per day
     * (#63). An item is in a day exactly when its record is [ArchiveRecord.onTimeline] for that day,
     * since a span `[first, last]` overlaps the day `[d, d + 1)` when `dayOf(first) <= d <= dayOf(last)`.
     * Each day keeps the [ArchiveRecord.TIMELINE_ORDER] of the time query.
     */
    fun <T> sourcesByDay(items: List<T>, record: (T) -> ArchiveRecord): Map<Long, List<T>> {
        val out = HashMap<Long, MutableList<T>>()
        val placed = items.mapNotNull { item -> record(item).let { r -> if (r.isIndexed && r.isTelemetry && r.timelineFirstNs != null && r.timelineLastNs != null) r to item else null } }
        for ((r, item) in placed.sortedWith(compareBy(ArchiveRecord.TIMELINE_ORDER) { it.first })) {
            var d = dayOf(r.timelineFirstNs!!)
            val lastDay = dayOf(r.timelineLastNs!!)
            while (d <= lastDay) { out.getOrPut(d) { ArrayList() } += item; d++ }
        }
        return out
    }

    /**
     * The derived days of one station a pass must keep although no indexed source touches them now,
     * because a record of that station [ArchiveRecord.awaitsIndex] and may still place itself there.
     * A waiting record that kept a timeline span from an earlier index holds the days of that span.
     * One with no span could land on any day, so it holds them all. Without this a pass that runs
     * while an index migration reopens records (Room v11, receipt layout 2) would delete days the
     * backfill is about to fill again (#87).
     */
    fun held(records: List<ArchiveRecord>): Held {
        val days = HashSet<Long>()
        for (r in records) {
            if (!r.awaitsIndex || !r.isTelemetry) continue
            val first = r.timelineFirstNs ?: return Held.ALL
            val last = r.timelineLastNs ?: return Held.ALL
            var d = dayOf(first)
            val lastDay = dayOf(last)
            while (d <= lastDay) { days += d; d++ }
        }
        return if (days.isEmpty()) Held.NONE else Held(false, days)
    }

    /** The days [held] keeps, every day when [all] is true. */
    data class Held(val all: Boolean, val days: Set<Long>) {
        operator fun contains(day: Long): Boolean = all || day in days

        companion object {
            val NONE = Held(false, emptySet())
            val ALL = Held(true, emptySet())
        }
    }
}
