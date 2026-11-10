package space.opensensor.data.history

import space.opensensor.model.H3Cell

/**
 * Where a row's location came from, the firmware `location_source` column (time-and-location.md).
 * 0 and null both mean the row has no location.
 */
enum class LocationSource(val wire: Int, val reported: Boolean) {
    /** The owner's configured cell of a fixed Thing. */
    CONFIGURED(1, reported = false),

    /** A fresh fix of the Thing's own GNSS receiver. */
    GNSS(2, reported = true),

    /** A cell the phone supplied as `location.phone_cell`. The firmware writes it, this app does not send it yet (#264). */
    PHONE(3, reported = true),
    ;

    companion object {
        /** The source of [wire], null for 0, null or a value the contract does not name. */
        fun fromWire(wire: Int?): LocationSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** One row's time and location as the file stores them. [cell] and [source] are null when the row has none. */
data class TrackRow(val epochNs: Long, val cell: Long?, val source: Int?)

/**
 * The places a Thing's rows put it, in time order (#263). Built only from each row's own
 * `h3_cell_id` and `location_source`. A row with no cell, no source or a cell that is not an H3 cell
 * is a row with no fix. It joins a [Span] with a null cell and never borrows a neighbour's cell, so
 * the track never shows a place the Thing did not report.
 */
data class CellTrack(val spans: List<Span>) {
    /**
     * Consecutive rows in one place. [cell] and [source] are null for rows with no fix. A Thing that
     * comes back to a cell later starts a new span, so the spans read as the route it took.
     */
    data class Span(val cell: Long?, val source: LocationSource?, val firstNs: Long, val lastNs: Long, val rows: Int) {
        val hasFix: Boolean get() = cell != null
    }

    /** The spans with a cell, in time order. */
    val fixes: List<Span> get() = spans.filter { it.hasFix }

    /** The newest place the rows reported, null when no row had a fix. */
    val lastFix: Span? get() = spans.lastOrNull { it.hasFix }

    /** Each cell once, in the order the Thing first reached it. */
    val cells: List<Long> get() = fixes.mapNotNull { it.cell }.distinct()

    /**
     * The spans a moving Thing reported as where it was, GNSS or phone fixes. A configured cell is
     * where the Thing stood while it was fixed, so it is never a moving Thing's place.
     */
    val reportedFixes: List<Span> get() = fixes.filter { it.source?.reported == true }

    /** The newest of [reportedFixes], null when the rows reported none. */
    val lastReportedFix: Span? get() = reportedFixes.lastOrNull()

    /** Each cell of [reportedFixes] once, in the order the Thing first reached it. */
    val reportedCells: List<Long> get() = reportedFixes.mapNotNull { it.cell }.distinct()

    /** Rows with no fix in the whole track. */
    val noFixRows: Int get() = spans.filter { !it.hasFix }.sumOf { it.rows }

    companion object {
        val EMPTY = CellTrack(emptyList())

        /** The track of [rows] in time order. Rows at the same time keep their file order. */
        fun of(rows: List<TrackRow>): CellTrack {
            if (rows.isEmpty()) return EMPTY
            val spans = ArrayList<Span>()
            var cell: Long? = null
            var source: LocationSource? = null
            var first = 0L
            var last = 0L
            var count = 0
            for (row in rows.sortedBy { it.epochNs }) {
                val fix = fixOf(row)
                val rowCell = fix?.first
                val rowSource = fix?.second
                if (count > 0 && rowCell == cell && rowSource == source) {
                    last = row.epochNs
                    count++
                    continue
                }
                if (count > 0) spans += Span(cell, source, first, last, count)
                cell = rowCell
                source = rowSource
                first = row.epochNs
                last = row.epochNs
                count = 1
            }
            spans += Span(cell, source, first, last, count)
            return CellTrack(spans)
        }

        /** The cell and source of [row], or null when the row has no fix. */
        private fun fixOf(row: TrackRow): Pair<Long, LocationSource>? {
            val cell = row.cell ?: return null
            val source = LocationSource.fromWire(row.source) ?: return null
            return if (H3Cell.isCell(cell)) cell to source else null
        }
    }
}
