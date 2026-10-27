package space.opensensor.family.air

import space.opensensor.data.history.HistoryPoint
import kotlin.math.floor

/**
 * The US EPA NowCast for particles (issue #93), the weighted mean AirNow uses to read the last 12
 * hourly means against a 24-hour scale. When the air is steady it leans towards the plain 12-hour
 * mean, and when the air changes fast it leans towards the latest hours.
 *
 * The method, as AirNow states it (forum.airnowtech.org/t/the-nowcast-for-ozone-and-pm/172)
 * 1. Take the hourly means c1 to c12, c1 the most recent complete hour.
 * 2. A NowCast needs at least two of the three most recent hours (c1, c2, c3).
 * 3. The weight factor is the lowest hour over the highest hour of those present, floored at 0.5
 *    for particles.
 * 4. The NowCast is the sum of weight^(i-1) times c_i over the sum of weight^(i-1), both over the
 *    present hours only, so a missing hour adds nothing to either sum.
 * 5. PM2.5 is truncated to 0.1 µg/m³, PM10 to 1 µg/m³.
 *
 * Pure and free of the clock, so both phones and the tests get the same number.
 */
object PmNowCast {
    /** Hours the NowCast reads, the latest first. */
    const val HOURS: Int = 12

    /** The lowest weight factor for particles. */
    const val MIN_WEIGHT: Double = 0.5

    /** PM2.5 NowCast step, in µg/m³. */
    const val PM25_STEP: Double = 0.1

    /** PM10 NowCast step, in µg/m³. */
    const val PM10_STEP: Double = 1.0

    private const val HOUR_S: Long = 3_600

    /**
     * The NowCast of [hourly], index 0 the most recent complete hour, or null when fewer than two of
     * the three most recent hours are present. A null or non-finite entry is a missing hour. Entries
     * past [HOURS] are ignored. The result is truncated down to a multiple of [step].
     */
    fun of(hourly: List<Double?>, step: Double = PM25_STEP): Double? {
        val hours = List(HOURS) { i -> hourly.getOrNull(i)?.takeIf { it.isFinite() } }
        if (hours.take(3).count { it != null } < 2) return null
        val present = hours.filterNotNull()
        val highest = present.max()
        val lowest = present.min()
        val weight = if (highest <= 0.0) 1.0 else maxOf(lowest / highest, MIN_WEIGHT)
        var factor = 1.0
        var top = 0.0
        var bottom = 0.0
        for (c in hours) {
            if (c != null) {
                top += factor * c
                bottom += factor
            }
            factor *= weight
        }
        // A tiny nudge keeps a value that is exactly on a step, such as 28.4, from flooring to 28.3.
        // Dividing by the whole number of steps per µg/m³ keeps the result the exact decimal, where
        // 284 times 0.1 is 28.400000000000002.
        val perUnit = 1.0 / step
        return floor(top / bottom * perUnit + 1e-9) / perUnit
    }

    /**
     * The [HOURS] hourly means that end at [currentHourStartS], the latest first. Slot i holds the
     * hour that starts at `currentHourStartS - (i + 1) * 3600`, so the hour still running is never
     * read. A slot is missing when no point starts there or its mean is null. Any verified row in
     * the hour gives it a mean, there is no completeness threshold.
     */
    fun hourlySlots(points: List<HistoryPoint>, currentHourStartS: Long): List<Double?> {
        val byStart = points.filter { it.endS - it.startS == HOUR_S }.associateBy { it.startS }
        return List(HOURS) { i -> byStart[currentHourStartS - (i + 1) * HOUR_S]?.mean?.toDouble() }
    }

    /** The UTC hour start at or before [epochS]. */
    fun hourStart(epochS: Long): Long = epochS.floorDiv(HOUR_S) * HOUR_S
}
