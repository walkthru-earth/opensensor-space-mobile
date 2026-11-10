package space.opensensor.history

import space.opensensor.data.history.CellTrack
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.LocationSource

/** An H3 cell of the window and the source of the newest rows in it. */
data class TrackCell(val cell: Long, val source: LocationSource)

/**
 * One stretch of the History track strip, [start] and [end] as shares of the window from 0 to 1.
 * [source] is null for rows with no fix.
 */
data class TrackBar(val start: Float, val end: Float, val source: LocationSource?)

/**
 * The places one window of History puts a Thing (#263), built only from its rows' own `h3_cell_id`
 * and `location_source` ([CellTrack]). [cells] are the distinct cells in the order the Thing first
 * reached them, [last] the cell of the newest fix, [bars] the strip in time order, [sources] the
 * sources the rows named in legend order and [noFix] true when some rows had no location. It holds
 * H3 cells, never a coordinate.
 */
data class HistoryTrack(
    val thing: String,
    val window: HistoryWindow,
    val cells: List<TrackCell>,
    val last: Long?,
    val bars: List<TrackBar>,
    val sources: List<LocationSource>,
    val noFix: Boolean,
) {
    companion object {
        /** The ranges that read a track. Month and Year would read every row of up to a year of files. */
        val RANGES: Set<HistoryRange> = setOf(HistoryRange.DAY, HistoryRange.WEEK)

        /**
         * Two spans this close in time join on the strip, so rows logged a few minutes apart read as
         * one stretch. A longer gap had no rows at all and stays empty.
         */
        const val JOIN_NS: Long = 15L * 60 * 1_000_000_000

        /**
         * The track of [window], or null when it has nothing to draw. It draws once a row reported a
         * GNSS or phone fix, or the rows name more than one cell. A fixed Thing whose rows only
         * ever name its one configured cell, or no cell, shows no track.
         */
        fun of(thing: String, window: HistoryWindow, track: CellTrack): HistoryTrack? {
            val fixes = track.fixes
            if (track.reportedFixes.isEmpty() && fixes.mapNotNull { it.cell }.distinct().size < 2) return null
            val newest = LinkedHashMap<Long, LocationSource>()
            for (span in fixes) {
                val cell = span.cell ?: continue
                val source = span.source ?: continue
                // The first visit fixes the order, the newest rows in the cell its source.
                newest[cell] = source
            }
            val fromNs = window.fromS * NS_PER_S
            val lengthNs = (window.toS - window.fromS).toDouble() * NS_PER_S
            val spans = track.spans
            val bars = spans.mapIndexed { i, span ->
                val next = spans.getOrNull(i + 1)?.firstNs
                val endNs = if (next != null && next - span.lastNs <= JOIN_NS) next else span.lastNs
                TrackBar(share(span.firstNs - fromNs, lengthNs), share(endNs - fromNs, lengthNs), span.source)
            }
            return HistoryTrack(
                thing = thing,
                window = window,
                cells = newest.map { (cell, source) -> TrackCell(cell, source) },
                last = track.lastFix?.cell,
                bars = bars,
                sources = LocationSource.entries.filter { s -> fixes.any { it.source == s } },
                noFix = track.noFixRows > 0,
            )
        }

        private fun share(ns: Long, lengthNs: Double): Float = (ns / lengthNs).coerceIn(0.0, 1.0).toFloat()

        private const val NS_PER_S = 1_000_000_000L
    }
}
