package earth.walkthru.aqsync.data.history

/** Visible span and which tier serves it. `DAY` reads the rows themselves (≤ 8,640 of them). */
enum class HistoryRange(val seconds: Long, val tier: Resolution?, val label: String) {
    DAY(86_400, null, "Day"),
    WEEK(7 * 86_400, Resolution.MIN15, "Week"),
    MONTH(30 * 86_400, Resolution.HOUR, "Month"),
    YEAR(365 * 86_400, Resolution.DAY, "Year"),
}

/**
 * One plotted element: a bucket (mean with min/max envelope) or a raw 10-second row (mean only).
 * [estimated]: the time axis for this point comes from a §4.6 estimate (drawn dashed), not a UTC stamp.
 */
data class HistoryPoint(val startS: Long, val endS: Long, val mean: Float?, val min: Float?, val max: Float?, val coverage: Float, val estimated: Boolean = false)

data class HistorySeries(
    val metric: MetricSpec,
    val range: HistoryRange,
    val fromS: Long,
    val toS: Long,
    val points: List<HistoryPoint>,
    /** Rows behind the means, and how many the logger would have produced over the whole span. */
    val validRows: Long,
    val expectedRows: Long,
    val mean: Float?,
    val max: Float?,
    /** True when the day view had to bin rows (more than [RAW_POINT_CAP]) into minutes. */
    val binned: Boolean,
    /** Rows on the estimated time axis (§4.6) among [validRows]-bearing rows. */
    val estimatedRows: Long = 0,
) {
    val hasEstimated: Boolean get() = estimatedRows > 0
    val coverage: Float get() = if (expectedRows == 0L) 0f else (validRows.toFloat() / expectedRows).coerceIn(0f, 1f)
    val isEmpty: Boolean get() = points.none { it.mean != null }
}
