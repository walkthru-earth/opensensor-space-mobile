package earth.walkthru.aqsync.publicfeed

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Folds the daily rows of one cells file (one row per `h3_index` and station local `date`, as in the
 * OpenAQ aggregates) into one [CellRow] per cell for a window. Pure, no I/O.
 *
 * - Only days in the 7 days ending at `asOf` (asOf minus 6 through asOf) count. Rows without a date
 *   are ignored.
 * - [FeedWindow.DAY]: per property, the newest day in range that has the property's mean. Its `n`,
 *   `mean`, `min` and `max` are copied as they are.
 * - [FeedWindow.WEEK]: per property, the mean of the daily means in range, the min of the mins, the
 *   max of the maxes and the largest daily `n`. Days without the property are skipped, never counted
 *   as zero, and a null min, max or n is skipped the same way.
 * - [FeedWindow.LAST_HOUR] has no daily answer, so it folds to nothing.
 * - A cell with no property in range is dropped. [CellRow.devices] is the largest daily `devices`
 *   among the days used, [CellRow.updatedEpochMs] the UTC midnight after the newest day used.
 *
 * Null stays null. No value is made up. Cells come out sorted by `h3_index`.
 */
object DailyCellFold {
    private const val DAY_MS = 86_400_000L
    private const val WINDOW_DAYS = 7

    fun fold(rows: List<CellRow>, window: FeedWindow, asOf: LocalDate): List<CellRow> {
        if (window == FeedWindow.LAST_HOUR) return emptyList()
        val first = asOf.minus(DatePeriod(days = WINDOW_DAYS - 1))
        val byCell = LinkedHashMap<Long, MutableList<CellRow>>()
        for (row in rows) {
            val date = row.date ?: continue
            if (date < first || date > asOf) continue
            byCell.getOrPut(row.h3) { ArrayList() } += row
        }
        val out = ArrayList<CellRow>(byCell.size)
        for (h3 in byCell.keys.sorted()) {
            val days = byCell.getValue(h3).sortedBy { it.date }
            val folded = when (window) {
                FeedWindow.DAY -> newest(h3, days)
                else -> week(h3, days)
            }
            if (folded != null) out += folded
        }
        return out
    }

    /** Per property, the newest day that has its mean. */
    private fun newest(h3: Long, days: List<CellRow>): CellRow? {
        val stats = LinkedHashMap<String, CellStat>()
        val used = LinkedHashSet<CellRow>()
        for (id in propertyIds(days)) {
            val day = days.lastOrNull { it.stats[id]?.mean != null } ?: continue
            stats[id] = day.stats.getValue(id)
            used += day
        }
        return cell(h3, stats, used)
    }

    private fun week(h3: Long, days: List<CellRow>): CellRow? {
        val stats = LinkedHashMap<String, CellStat>()
        val used = LinkedHashSet<CellRow>()
        for (id in propertyIds(days)) {
            val measured = days.filter { it.stats[id]?.mean != null }
            if (measured.isEmpty()) continue
            val daily = measured.map { it.stats.getValue(id) }
            stats[id] = CellStat(
                n = daily.mapNotNull { it.n }.maxOrNull(),
                mean = daily.sumOf { it.mean!! } / daily.size,
                min = daily.mapNotNull { it.min }.minOrNull(),
                max = daily.mapNotNull { it.max }.maxOrNull(),
            )
            used += measured
        }
        return cell(h3, stats, used)
    }

    private fun propertyIds(days: List<CellRow>): Set<String> = days.flatMapTo(LinkedHashSet()) { it.stats.keys }

    private fun cell(h3: Long, stats: Map<String, CellStat>, used: Set<CellRow>): CellRow? {
        if (stats.isEmpty()) return null
        val newest = used.maxOf { it.date!! }
        return CellRow(
            h3 = h3,
            devices = used.maxOf { it.devices },
            updatedEpochMs = (newest.toEpochDays() + 1) * DAY_MS,
            stats = stats,
        )
    }
}
