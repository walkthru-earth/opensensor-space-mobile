package space.opensensor.publicfeed

import space.opensensor.airquality.MoodBlock
import space.opensensor.airquality.ThingSummary

/**
 * The public summary of one cell. The windows of its feed, oldest first (7 days, 24 hours, last hour),
 * one block each, so a feed without the last hour has two blocks. The headline is the 24 hour mean, the averaging of the WHO guideline. A window that
 * was not read, or holds no stat, is a null block, never zero. The update time is the 24 hour
 * row's too, so a newer hour never dates a headline that is not available. It is the date of
 * the property itself ([CellRow.updatedEpochMs]), so a newer reading of another property never dates it.
 * Steps and blocks come from the property's WHO scale ([StepScale.who]). Without one the number
 * shows alone.
 */
object PublicSummary {
    val ORDER: List<FeedWindow> = listOf(FeedWindow.WEEK, FeedWindow.DAY, FeedWindow.LAST_HOUR)

    fun of(property: FeedProperty, windows: Map<FeedWindow, CellRow?>, offered: List<FeedWindow> = ORDER): ThingSummary {
        val id = property.id
        val who = property.scale?.let { StepScale.of(id, it) }?.who
        val value = windows[FeedWindow.DAY]?.mean(id)?.takeIf { it.isFinite() }
        val history = ORDER.filter { it in offered }.map { windows[it]?.mean(id) }
        return ThingSummary(
            propertyId = id,
            value = value,
            unit = property.unit,
            step = value?.let { who?.stepOf(it) },
            scaled = who != null,
            updatedEpochMs = windows[FeedWindow.DAY]?.updatedEpochMs(id),
            blocks = if (who == null) emptyList() else history.map { v -> MoodBlock(v?.takeIf { it.isFinite() }?.let(who::stepOf)) },
        )
    }
}
