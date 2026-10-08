package earth.walkthru.opensensor.discover

import androidx.compose.runtime.Immutable
import earth.walkthru.aqsync.airquality.WhoStep

/**
 * Every word Discover shows. Android builds this from `res/values/strings.xml` (so `CopyRulesTest`
 * checks it and translations work), iOS uses [English] until it has its own string catalogue.
 * Copy rules. No em or en dashes, no colons or semicolons. Minimal copy, explanations live in
 * info sheets.
 */
@Immutable
data class DiscoverStrings(
    val searchPlaces: String,
    val addSensor: String,
    val more: String,
    val windowLastHour: String,
    val window24Hours: String,
    val window7Days: String,
    val sampleData: String,
    val sampleInfo: InfoText,
    val scaleInfo: InfoText,
    val osm: String,
    val mapInfo: InfoText,
    val noAreas: String,
    val notMeasured: String,
    val stale: String,
    val avg: String,
    val min: String,
    val max: String,
    val firstRunLine: String,
    val explore: String,
    val allMeasurements: String,
    val unavailable: String,
    val loading: String,
    val noPlaces: String,
    val clearSearch: String,
    val areasInView: (Int) -> String,
    val sensors: (Int) -> String,
    val updatedHoursAgo: (Int) -> String,
    val updatedRecently: String,
    /** Under the hero chart, where the area sits among the areas in view (percent below). */
    val higherThan: (Int) -> String = { n -> "Higher than $n% of areas in view" },
    /** Spoken summary of one range row (label, average, min, max, unit). Min or max may be null. */
    val rangeSummary: (String, String, String?, String?, String) -> String = { label, avg, min, max, unit ->
        if (min != null && max != null) "$label average $avg, range $min to $max $unit" else "$label average $avg $unit"
    },
    /** Spoken summary of a distribution chart (label, areas, lowest, highest, unit). */
    val spreadSummary: (String, Int, String, String, String) -> String = { label, n, low, high, unit ->
        "$label across $n areas, from $low to $high $unit"
    },
    /** The map button that centres on approximate location, spoken only. */
    val nearMe: String = "Near me",
    /** Short labels beside the time slider stops. The full window names are spoken. */
    val windowShortHour: String = "1h",
    val windowShortDay: String = "24h",
    val windowShortWeek: String = "7d",
    /** The time pill, spoken only. Its state is the full window name. */
    val timeWindow: String = "Time window",
    /** Names of the six WHO steps, cleanest first, in [WhoStep] order. Shown beside values, so colour is never the only cue. */
    val whoSteps: List<String> = listOf(
        "Meets WHO guideline",
        "Interim target 4",
        "Interim target 3",
        "Interim target 2",
        "Interim target 1",
        "Above all targets",
    ),
    /** The short mark at the guideline on the legend and the charts. */
    val whoMark: String = "WHO",
    /** Legend caption in the 24 hour window, where the means match the guideline's averaging. */
    val whoCaptionDay: String = "WHO 24-hour guideline",
    /** Legend caption in the last hour and 7 day windows. */
    val whoCaptionCompared: String = "Compared with the WHO 24-hour guideline",
    /** Legend caption for a property without a published health scale. */
    val noHealthScale: String = "No health scale published",
    /** The legend info sheet for PM2.5 and PM10. */
    val whoInfo: InfoText = InfoText(
        "WHO air quality guideline",
        listOf(
            "Colours are fixed steps from the WHO global air quality guidelines, 2021, so a colour means the same in every place and every view.",
            "The lightest green meets the WHO guideline. Interim targets 4 to 1 are steps on the way to it, each less strict than the one before. The darkest colour is above all four targets.",
            "The guideline and the targets are 24-hour means. In the last hour and 7 day views, those means are compared with the same values.",
            "Each area shows hourly means from three or more sensors. A faded area has no new value for 3 hours. An outline only means the area does not measure this.",
        ),
    ),
    /** Under a chart whose values run past its axis (highest value). */
    val offScale: (String) -> String = { v -> "Some areas go past the chart, up to $v" },
    /** Spoken legend step (step name, upper edge or null for the last step, unit). */
    val whoStepSpoken: (String, String?, String) -> String = { name, upTo, unit -> if (upTo != null) "$name, up to $upTo $unit" else name },
) {
    /** The name of [step], from [whoSteps]. */
    fun stepName(step: WhoStep): String = whoSteps.getOrElse(step.ordinal) { step.name }

    companion object {
        /** English copy for iOS. Keep it in step with the Android `discover_*` strings. */
        val English = DiscoverStrings(
            searchPlaces = "Search places",
            addSensor = "Add your sensor",
            more = "More",
            windowLastHour = "Last hour",
            window24Hours = "24 hours",
            window7Days = "7 days",
            sampleData = "Sample data",
            sampleInfo = InfoText(
                "Sample data",
                listOf("These numbers are made up for a preview. Real sensors appear here when the public feed goes live."),
            ),
            scaleInfo = InfoText(
                "Colour scale",
                listOf(
                    "No health scale is published for this measurement. Colours stretch from low to high across the areas in view, leaving out the lowest and highest 5 percent.",
                    "Each area shows hourly means from three or more sensors. A faded area has no new value for 3 hours. An outline only means the area does not measure this.",
                ),
            ),
            osm = "© OpenStreetMap",
            mapInfo = InfoText(
                "Map data",
                listOf(
                    "Map data © OpenStreetMap contributors, under the Open Database License. See openstreetmap.org/copyright.",
                    "Basemap by Protomaps.",
                ),
            ),
            noAreas = "No sensor areas here yet",
            notMeasured = "Not measured here",
            stale = "No new value for 3 hours",
            avg = "avg",
            min = "min",
            max = "max",
            firstRunLine = "Public sensors near you, shown as areas",
            explore = "Explore",
            allMeasurements = "All measurements",
            unavailable = "Public sensors are not available on this phone yet",
            loading = "Loading",
            noPlaces = "No matching places",
            clearSearch = "Clear search",
            areasInView = { n -> if (n == 1) "1 sensor area in view" else "$n sensor areas in view" },
            sensors = { n -> if (n == 1) "1 sensor" else "$n sensors" },
            updatedHoursAgo = { n -> if (n == 1) "Updated 1 h ago" else "Updated $n h ago" },
            updatedRecently = "Updated within the hour",
        )
    }
}

/** A title and short paragraphs for an info sheet. */
@Immutable
data class InfoText(val title: String, val body: List<String>)
