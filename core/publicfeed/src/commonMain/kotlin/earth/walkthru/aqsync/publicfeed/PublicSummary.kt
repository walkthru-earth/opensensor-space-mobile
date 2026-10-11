package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.airquality.StationSummary

/**
 * The public summary of one cell. Three windows, oldest first (7 days, 24 hours, last hour), one
 * block each. The headline is the 24 hour mean, the averaging of the WHO guideline. A window that
 * was not read, or holds no stat, is a null block, never zero. The update time is the 24 hour
 * row's too, so a newer hour never dates a headline that is not available.
 */
object PublicSummary {
    val ORDER: List<FeedWindow> = listOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR)

    fun of(propertyId: String, unit: String, windows: Map<FeedWindow, CellRow?>): StationSummary = StationSummary.of(
        propertyId = propertyId,
        unit = unit,
        value = windows[FeedWindow.DAY]?.mean(propertyId),
        updatedEpochMs = windows[FeedWindow.DAY]?.updatedEpochMs,
        history = ORDER.map { windows[it]?.mean(propertyId) },
    )
}
