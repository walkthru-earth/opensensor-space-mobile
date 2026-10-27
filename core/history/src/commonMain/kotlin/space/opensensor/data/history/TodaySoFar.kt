package space.opensensor.data.history

/**
 * "Today so far" from the hourly tier, a real average of the thing's main [metric] with its coverage,
 * or nothing. A family may pick a corrected view as [metric] when the day has rows it can correct,
 * and its [MetricSpec.basis] says which.
 */
data class TodaySoFar(val primaryMean: Float, val validRows: Int, val coverage: Float, val metric: MetricSpec)
