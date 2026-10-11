package earth.walkthru.opensensor.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.opensensor.discover.resources.Res
import earth.walkthru.opensensor.discover.resources.discover_access_cancel
import earth.walkthru.opensensor.discover.resources.discover_access_continue
import earth.walkthru.opensensor.discover.resources.discover_access_not_now
import earth.walkthru.opensensor.discover.resources.discover_access_open_settings
import earth.walkthru.opensensor.discover.resources.discover_add_sensor
import earth.walkthru.opensensor.discover.resources.discover_all_measurements
import earth.walkthru.opensensor.discover.resources.discover_area_count
import earth.walkthru.opensensor.discover.resources.discover_areas_in_view
import earth.walkthru.opensensor.discover.resources.discover_average_summary
import earth.walkthru.opensensor.discover.resources.discover_avg
import earth.walkthru.opensensor.discover.resources.discover_clear_search
import earth.walkthru.opensensor.discover.resources.discover_explore
import earth.walkthru.opensensor.discover.resources.discover_first_run
import earth.walkthru.opensensor.discover.resources.discover_higher_than
import earth.walkthru.opensensor.discover.resources.discover_loading
import earth.walkthru.opensensor.discover.resources.discover_map_info1
import earth.walkthru.opensensor.discover.resources.discover_map_info2
import earth.walkthru.opensensor.discover.resources.discover_map_label
import earth.walkthru.opensensor.discover.resources.discover_map_title
import earth.walkthru.opensensor.discover.resources.discover_max
import earth.walkthru.opensensor.discover.resources.discover_min
import earth.walkthru.opensensor.discover.resources.discover_mood_info1
import earth.walkthru.opensensor.discover.resources.discover_mood_info2
import earth.walkthru.opensensor.discover.resources.discover_mood_info_title
import earth.walkthru.opensensor.discover.resources.discover_more
import earth.walkthru.opensensor.discover.resources.discover_near_me
import earth.walkthru.opensensor.discover.resources.discover_near_me_access_body
import earth.walkthru.opensensor.discover.resources.discover_near_me_access_title
import earth.walkthru.opensensor.discover.resources.discover_near_me_settings_body
import earth.walkthru.opensensor.discover.resources.discover_near_me_settings_title
import earth.walkthru.opensensor.discover.resources.discover_no_areas
import earth.walkthru.opensensor.discover.resources.discover_no_health_scale
import earth.walkthru.opensensor.discover.resources.discover_no_places
import earth.walkthru.opensensor.discover.resources.discover_not_measured
import earth.walkthru.opensensor.discover.resources.discover_off_scale
import earth.walkthru.opensensor.discover.resources.discover_osm
import earth.walkthru.opensensor.discover.resources.discover_range_summary
import earth.walkthru.opensensor.discover.resources.discover_range_to
import earth.walkthru.opensensor.discover.resources.discover_sample_data
import earth.walkthru.opensensor.discover.resources.discover_sample_info
import earth.walkthru.opensensor.discover.resources.discover_scale_info1
import earth.walkthru.opensensor.discover.resources.discover_scale_info2
import earth.walkthru.opensensor.discover.resources.discover_scale_title
import earth.walkthru.opensensor.discover.resources.discover_search_places
import earth.walkthru.opensensor.discover.resources.discover_sensors
import earth.walkthru.opensensor.discover.resources.discover_spread_peak
import earth.walkthru.opensensor.discover.resources.discover_spread_summary
import earth.walkthru.opensensor.discover.resources.discover_stale
import earth.walkthru.opensensor.discover.resources.discover_time_window
import earth.walkthru.opensensor.discover.resources.discover_unavailable
import earth.walkthru.opensensor.discover.resources.discover_updated_hours_ago
import earth.walkthru.opensensor.discover.resources.discover_updated_recently
import earth.walkthru.opensensor.discover.resources.discover_who_caption_compared
import earth.walkthru.opensensor.discover.resources.discover_who_caption_day
import earth.walkthru.opensensor.discover.resources.discover_who_info1
import earth.walkthru.opensensor.discover.resources.discover_who_info2
import earth.walkthru.opensensor.discover.resources.discover_who_info3
import earth.walkthru.opensensor.discover.resources.discover_who_mark
import earth.walkthru.opensensor.discover.resources.discover_who_step_spoken
import earth.walkthru.opensensor.discover.resources.discover_who_title
import earth.walkthru.opensensor.discover.resources.discover_window_24_hours
import earth.walkthru.opensensor.discover.resources.discover_window_7_days
import earth.walkthru.opensensor.discover.resources.discover_window_last_hour
import earth.walkthru.opensensor.discover.resources.discover_window_short_day
import earth.walkthru.opensensor.discover.resources.discover_window_short_hour
import earth.walkthru.opensensor.discover.resources.discover_window_short_week
import earth.walkthru.opensensor.discover.resources.who_step_above_it1
import earth.walkthru.opensensor.discover.resources.who_step_guideline
import earth.walkthru.opensensor.discover.resources.who_step_it1
import earth.walkthru.opensensor.discover.resources.who_step_it2
import earth.walkthru.opensensor.discover.resources.who_step_it3
import earth.walkthru.opensensor.discover.resources.who_step_it4
import earth.walkthru.opensensor.permissions.AccessDialogTexts
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every word Discover shows, read from this module's Compose resources
 * (`composeResources/values/strings.xml`), one catalogue for Android and iOS. Build it with
 * [discoverStrings]. Text with a count or arguments is a composable function, so plurals follow the
 * phone's language. Copy rules. No em or en dashes, no colons or semicolons. Minimal copy,
 * explanations live in info sheets.
 */
@Immutable
class DiscoverStrings internal constructor(
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
    val updatedRecently: String,
    /** The map button that centres on approximate location, spoken only. */
    val nearMe: String,
    /**
     * Near me's approximate location dialogs. The rationale shows only when Android says it is due,
     * the settings dialog only after the system stops asking. iOS never shows the rationale.
     */
    val nearMeAccess: AccessDialogTexts,
    /** Short labels beside the time slider stops. The full window names are spoken. */
    val windowShortHour: String,
    val windowShortDay: String,
    val windowShortWeek: String,
    /** The time pill, spoken only. Its state is the full window name. */
    val timeWindow: String,
    /** Names of the six WHO steps, cleanest first, in [WhoStep] order. Shown beside values, so colour is never the only cue. */
    val whoSteps: List<String>,
    /** The short mark at the guideline on the legend and the charts. */
    val whoMark: String,
    /** Legend caption in the 24 hour window, where the means match the guideline's averaging. */
    val whoCaptionDay: String,
    /** Legend caption in the last hour and 7 day windows. */
    val whoCaptionCompared: String,
    /** Legend caption for a property without a published health scale. */
    val noHealthScale: String,
    /** The legend info sheet for PM2.5 and PM10. */
    val whoInfo: InfoText,

    /** The legend info sheet at the Simple level. No guideline body, no numbers. */
    val moodInfo: InfoText,
    /** Joins two ends of a range, as in "15 to 25". */
    val to: String,
    /** The map's spoken name, followed by the chosen measurement. */
    val mapLabel: String,
) {
    /** The name of [step], from [whoSteps]. */
    fun stepName(step: WhoStep): String = whoSteps.getOrElse(step.ordinal) { step.name }

    @Composable
    fun areasInView(n: Int): String = pluralStringResource(Res.plurals.discover_areas_in_view, n, n)

    @Composable
    fun sensors(n: Int): String = pluralStringResource(Res.plurals.discover_sensors, n, n)

    @Composable
    fun updatedHoursAgo(n: Int): String = pluralStringResource(Res.plurals.discover_updated_hours_ago, n, n)

    /** Under the hero chart, where the area sits among the areas in view (percent below). */
    @Composable
    fun higherThan(percent: Int): String = stringResource(Res.string.discover_higher_than, percent)

    /** Spoken summary of one range row. [min] or [max] may be null. */
    @Composable
    fun rangeSummary(label: String, avg: String, min: String?, max: String?, unit: String): String =
        if (min != null && max != null) {
            stringResource(Res.string.discover_range_summary, label, avg, min, max, unit)
        } else {
            stringResource(Res.string.discover_average_summary, label, avg, unit)
        }

    /** Spoken summary of a distribution chart. */
    @Composable
    fun spreadSummary(label: String, areas: Int, low: String, high: String, unit: String): String =
        pluralStringResource(Res.plurals.discover_spread_summary, areas, label, areas, low, high, unit)

    /** Spoken with a distribution chart, the range of the bar that holds the most areas. */
    @Composable
    fun spreadPeak(from: String, to: String, unit: String): String = stringResource(Res.string.discover_spread_peak, from, to, unit)

    /** Under a chart whose values run past its axis (highest value). */
    @Composable
    fun offScale(value: String): String = stringResource(Res.string.discover_off_scale, value)

    /** How many areas a touched bar holds. */
    @Composable
    fun areaCount(n: Int): String = pluralStringResource(Res.plurals.discover_area_count, n, n)

    /** Spoken legend step. [upTo] is null for the last step. */
    @Composable
    fun whoStepSpoken(name: String, upTo: String?, unit: String): String =
        if (upTo != null) stringResource(Res.string.discover_who_step_spoken, name, upTo, unit) else name
}

/** Discover's copy in the phone's language, from the shared Compose resources. */
@Composable
fun discoverStrings(): DiscoverStrings = DiscoverStrings(
    searchPlaces = stringResource(Res.string.discover_search_places),
    addSensor = stringResource(Res.string.discover_add_sensor),
    more = stringResource(Res.string.discover_more),
    windowLastHour = stringResource(Res.string.discover_window_last_hour),
    window24Hours = stringResource(Res.string.discover_window_24_hours),
    window7Days = stringResource(Res.string.discover_window_7_days),
    sampleData = stringResource(Res.string.discover_sample_data),
    sampleInfo = InfoText(stringResource(Res.string.discover_sample_data), listOf(stringResource(Res.string.discover_sample_info))),
    scaleInfo = InfoText(
        stringResource(Res.string.discover_scale_title),
        listOf(stringResource(Res.string.discover_scale_info1), stringResource(Res.string.discover_scale_info2)),
    ),
    osm = stringResource(Res.string.discover_osm),
    mapInfo = InfoText(
        stringResource(Res.string.discover_map_title),
        listOf(stringResource(Res.string.discover_map_info1), stringResource(Res.string.discover_map_info2)),
    ),
    noAreas = stringResource(Res.string.discover_no_areas),
    notMeasured = stringResource(Res.string.discover_not_measured),
    stale = stringResource(Res.string.discover_stale),
    avg = stringResource(Res.string.discover_avg),
    min = stringResource(Res.string.discover_min),
    max = stringResource(Res.string.discover_max),
    firstRunLine = stringResource(Res.string.discover_first_run),
    explore = stringResource(Res.string.discover_explore),
    allMeasurements = stringResource(Res.string.discover_all_measurements),
    unavailable = stringResource(Res.string.discover_unavailable),
    loading = stringResource(Res.string.discover_loading),
    noPlaces = stringResource(Res.string.discover_no_places),
    clearSearch = stringResource(Res.string.discover_clear_search),
    updatedRecently = stringResource(Res.string.discover_updated_recently),
    nearMe = stringResource(Res.string.discover_near_me),
    nearMeAccess = AccessDialogTexts(
        rationaleTitle = stringResource(Res.string.discover_near_me_access_title),
        rationaleBody = stringResource(Res.string.discover_near_me_access_body),
        settingsTitle = stringResource(Res.string.discover_near_me_settings_title),
        settingsBody = stringResource(Res.string.discover_near_me_settings_body),
        continueLabel = stringResource(Res.string.discover_access_continue),
        notNow = stringResource(Res.string.discover_access_not_now),
        openSettings = stringResource(Res.string.discover_access_open_settings),
        cancel = stringResource(Res.string.discover_access_cancel),
        icon = DiscoverIcons.MyLocation,
    ),
    windowShortHour = stringResource(Res.string.discover_window_short_hour),
    windowShortDay = stringResource(Res.string.discover_window_short_day),
    windowShortWeek = stringResource(Res.string.discover_window_short_week),
    timeWindow = stringResource(Res.string.discover_time_window),
    whoSteps = listOf(
        stringResource(Res.string.who_step_guideline),
        stringResource(Res.string.who_step_it4),
        stringResource(Res.string.who_step_it3),
        stringResource(Res.string.who_step_it2),
        stringResource(Res.string.who_step_it1),
        stringResource(Res.string.who_step_above_it1),
    ),
    whoMark = stringResource(Res.string.discover_who_mark),
    whoCaptionDay = stringResource(Res.string.discover_who_caption_day),
    whoCaptionCompared = stringResource(Res.string.discover_who_caption_compared),
    noHealthScale = stringResource(Res.string.discover_no_health_scale),
    whoInfo = InfoText(
        stringResource(Res.string.discover_who_title),
        listOf(
            stringResource(Res.string.discover_who_info1),
            stringResource(Res.string.discover_who_info2),
            stringResource(Res.string.discover_who_info3),
            stringResource(Res.string.discover_scale_info2),
        ),
    ),
    moodInfo = InfoText(
        stringResource(Res.string.discover_mood_info_title),
        listOf(stringResource(Res.string.discover_mood_info1), stringResource(Res.string.discover_mood_info2)),
    ),
    to = stringResource(Res.string.discover_range_to),
    mapLabel = stringResource(Res.string.discover_map_label),
)

/** A title and short paragraphs for an info sheet. */
@Immutable
data class InfoText(val title: String, val body: List<String>)
