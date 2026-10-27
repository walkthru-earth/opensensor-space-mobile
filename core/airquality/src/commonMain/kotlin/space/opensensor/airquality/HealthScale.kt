package space.opensensor.airquality

/**
 * A published health scale for one property, read against fixed edges in the property's own unit.
 *
 * The WHO 2021 Air Quality Guidelines give each pollutant a guideline level and up to four interim
 * targets, steps a place can pass on its way down to the guideline. A value sits in one of six
 * steps, from [WhoStep.GUIDELINE] (at or below the guideline) to [WhoStep.ABOVE_IT1] (above the
 * loosest interim target). The guideline and target values are 24-hour means. A 1-hour or 7-day
 * mean is compared against them too, and the screen says so ([averaging]).
 *
 * Edges are inclusive from below, as WHO states them ("at or below 15 µg/m³ meets the guideline").
 * Properties without a WHO value (CO₂, temperature, humidity) have no scale and stay neutral.
 */
data class HealthScale(
    /** The property id this scale reads, the same id the metric registry and the public feed use. */
    val propertyId: String,
    /** The unit the edges are in. The scale never converts. */
    val unit: String,
    /** Upper edges of steps 0 to 4, ascending, guideline first then IT-4, IT-3, IT-2, IT-1. */
    val edges: List<Double>,
    /** The averaging time the edges were set for. */
    val averaging: Averaging,
    /** The publication, for the legend's info sheet and the docs. */
    val source: String,
) {
    init {
        require(edges.size == WhoStep.entries.size - 1) { "a WHO scale has one edge per step boundary" }
        require(edges.zipWithNext().all { (a, b) -> a < b }) { "edges ascend" }
    }

    /** The guideline value itself, the one line every chart draws. */
    val guideline: Double get() = edges.first()

    /** The interim target 3 edge, the lowest top a chart axis takes, so a normal day keeps the guideline readable. */
    val axisFloor: Double get() = edges[WhoStep.IT3.ordinal]

    /** The step [value] falls in. Non-finite values have no step. */
    fun stepOf(value: Double): WhoStep? {
        if (!value.isFinite()) return null
        val i = edges.indexOfFirst { value <= it }
        return WhoStep.entries[if (i < 0) edges.size else i]
    }

    /** ARGB colour of [value]'s step, or null for a non-finite value. */
    fun colorOf(value: Double): Int? = stepOf(value)?.color

    /** The value range of [step], lower bound exclusive (null below the first step), upper inclusive (null above the last). */
    fun rangeOf(step: WhoStep): Pair<Double?, Double?> = edges.getOrNull(step.ordinal - 1) to edges.getOrNull(step.ordinal)
}

enum class Averaging { HOURS_24, ANNUAL }

/**
 * The six steps of a WHO scale, cleanest first. Colours run light to dark (green, yellow, orange,
 * red, purple, maroon) so the order survives greyscale, and neighbours stay apart under
 * deuteranopia, protanopia and tritanopia simulation (`WhoPaletteTest`). The order and hues follow
 * the accessible AQI scale South Coast AQMD built with US EPA, so they read as "air quality" at a
 * glance. They are never brand colours.
 */
enum class WhoStep(val color: Int) {
    /** At or below the WHO guideline. */
    GUIDELINE(0xFFC6EBA8.toInt()),

    /** Above the guideline, at or below interim target 4. */
    IT4(0xFFF5D75E.toInt()),
    IT3(0xFFF2A14A.toInt()),
    IT2(0xFFDC5A4A.toInt()),
    IT1(0xFF9A3F8E.toInt()),

    /** Above the loosest interim target. */
    ABOVE_IT1(0xFF5C1A33.toInt()),
    ;

    /** True when text on this colour should be light. */
    val darkFill: Boolean get() = this == IT1 || this == ABOVE_IT1
}

/** The WHO 2021 Air Quality Guidelines, 24-hour means. */
object WhoGuidelines {
    const val SOURCE = "WHO global air quality guidelines, 2021"
    const val URL = "https://www.who.int/publications/i/item/9789240034228"

    val PM25 = HealthScale("pm25", "µg/m³", listOf(15.0, 25.0, 37.5, 50.0, 75.0), Averaging.HOURS_24, SOURCE)
    val PM10 = HealthScale("pm10", "µg/m³", listOf(45.0, 50.0, 75.0, 100.0, 150.0), Averaging.HOURS_24, SOURCE)

    val all: List<HealthScale> = listOf(PM25, PM10)

    /** The scale for [propertyId], or null when WHO publishes none for it. */
    fun forProperty(propertyId: String): HealthScale? = all.firstOrNull { it.propertyId == propertyId }
}
