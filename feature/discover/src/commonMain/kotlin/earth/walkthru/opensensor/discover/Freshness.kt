package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.CellFreshness
import kotlinx.datetime.LocalDate

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
