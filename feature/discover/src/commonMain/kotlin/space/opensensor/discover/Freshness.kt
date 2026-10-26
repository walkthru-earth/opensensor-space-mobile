package space.opensensor.discover

import kotlinx.datetime.LocalDate
import space.opensensor.publicfeed.CellFreshness
import space.opensensor.publicfeed.CellRow

/** How fresh a value reads in the sheet. */
internal sealed interface Freshness {
    /** An hourly value with nothing new for 3 hours. */
    data object Stale : Freshness

    data object Recent : Freshness

    data class HoursAgo(val hours: Int) : Freshness

    /** A daily value, named by the last day it measured, never by hours on the clock. */
    data class DataTo(val day: LocalDate) : Freshness
}

/**
 * The freshness of a value updated at [updated]. A daily feed passes its partition date as [asOf],
 * and the value is named by its own day. An hourly feed is read against [reference].
 */
internal fun freshnessOf(updated: Long?, reference: Long, asOf: LocalDate?, staleShown: Boolean = true): Freshness? {
    updated ?: return null
    if (asOf != null) return Freshness.DataTo(CellFreshness.dataDay(updated))
    if (staleShown && reference - updated >= CellFreshness.STALE_AFTER_MS) return Freshness.Stale
    val hours = ((reference - updated) / 3_600_000L).toInt()
    return if (hours < 1) Freshness.Recent else Freshness.HoursAgo(hours)
}

/** The partition date when the feed on screen is daily, else null. */
internal val DiscoverUiState.dailyAsOf: LocalDate? get() = dataDate?.takeIf { daily }

/**
 * The newest update of the property on screen across the cells in view, each dated by that
 * property's own update. Cells without the property do not count. With no property, the newest row.
 */
internal val DiscoverUiState.newestShownUpdateEpochMs: Long?
    get() {
        val p = property ?: return newestUpdateEpochMs
        return rows.filter { it.mean(p.id) != null }.maxOfOrNull { it.updatedEpochMs(p.id) }
    }

/** The map caption under the count of areas in view. It never goes stale, it names the newest value. */
internal fun DiscoverUiState.inViewFreshness(reference: Long): Freshness? =
    freshnessOf(newestShownUpdateEpochMs, reference, dailyAsOf, staleShown = false)

/** The Detailed sheet's freshness of [row], dated by the shown property's own update. */
internal fun DiscoverUiState.cellFreshness(row: CellRow, reference: Long): Freshness? =
    freshnessOf(row.updatedEpochMs(property?.id), reference, dailyAsOf)
