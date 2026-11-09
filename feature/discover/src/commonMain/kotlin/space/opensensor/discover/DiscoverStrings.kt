package space.opensensor.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.preferredLanguage
import space.opensensor.discover.resources.Res
import space.opensensor.discover.resources.discover_add_sensor
import space.opensensor.discover.resources.discover_ago_days
import space.opensensor.discover.resources.discover_ago_hours
import space.opensensor.discover.resources.discover_ago_just_now
import space.opensensor.discover.resources.discover_ago_minutes
import space.opensensor.discover.resources.discover_all_measurements
import space.opensensor.discover.resources.discover_area_count
import space.opensensor.discover.resources.discover_areas_in_view
import space.opensensor.discover.resources.discover_average_summary
import space.opensensor.discover.resources.discover_avg
import space.opensensor.discover.resources.discover_clear_filter
import space.opensensor.discover.resources.discover_clear_search
import space.opensensor.discover.resources.discover_data_openaq
import space.opensensor.discover.resources.discover_data_to
import space.opensensor.discover.resources.discover_day_month
import space.opensensor.discover.resources.discover_explore
import space.opensensor.discover.resources.discover_first_run
import space.opensensor.discover.resources.discover_higher_than
import space.opensensor.discover.resources.discover_includes_own
import space.opensensor.discover.resources.discover_live
import space.opensensor.discover.resources.discover_load_failed
import space.opensensor.discover.resources.discover_loading
import space.opensensor.discover.resources.discover_map_info1
import space.opensensor.discover.resources.discover_map_info2
import space.opensensor.discover.resources.discover_map_info3
import space.opensensor.discover.resources.discover_map_label
import space.opensensor.discover.resources.discover_map_title
import space.opensensor.discover.resources.discover_max
import space.opensensor.discover.resources.discover_min
import space.opensensor.discover.resources.discover_months_short
import space.opensensor.discover.resources.discover_mood_info1
import space.opensensor.discover.resources.discover_mood_info2
import space.opensensor.discover.resources.discover_mood_info_title
import space.opensensor.discover.resources.discover_more
import space.opensensor.discover.resources.discover_no_areas
import space.opensensor.discover.resources.discover_no_health_scale
import space.opensensor.discover.resources.discover_no_places
import space.opensensor.discover.resources.discover_not_measured
import space.opensensor.discover.resources.discover_off_scale
import space.opensensor.discover.resources.discover_osm
import space.opensensor.discover.resources.discover_own_heading
import space.opensensor.discover.resources.discover_own_show
import space.opensensor.discover.resources.discover_own_unnamed
import space.opensensor.discover.resources.discover_range_summary
import space.opensensor.discover.resources.discover_range_to
import space.opensensor.discover.resources.discover_read_failed
import space.opensensor.discover.resources.discover_real_cached
import space.opensensor.discover.resources.discover_real_data_to
import space.opensensor.discover.resources.discover_real_info
import space.opensensor.discover.resources.discover_real_title
import space.opensensor.discover.resources.discover_retry
import space.opensensor.discover.resources.discover_sample_data
import space.opensensor.discover.resources.discover_sample_info
import space.opensensor.discover.resources.discover_scale_info1
import space.opensensor.discover.resources.discover_scale_info2
import space.opensensor.discover.resources.discover_scale_info2_real
import space.opensensor.discover.resources.discover_scale_mark
import space.opensensor.discover.resources.discover_scale_title
import space.opensensor.discover.resources.discover_search_places
import space.opensensor.discover.resources.discover_sensors
import space.opensensor.discover.resources.discover_sheet_collapse
import space.opensensor.discover.resources.discover_sheet_expand
import space.opensensor.discover.resources.discover_source_label
import space.opensensor.discover.resources.discover_source_off
import space.opensensor.discover.resources.discover_source_real
import space.opensensor.discover.resources.discover_source_sample
import space.opensensor.discover.resources.discover_sources_own
import space.opensensor.discover.resources.discover_sources_own_none
import space.opensensor.discover.resources.discover_sources_public
import space.opensensor.discover.resources.discover_sources_public_cached
import space.opensensor.discover.resources.discover_sources_sample
import space.opensensor.discover.resources.discover_spread_peak
import space.opensensor.discover.resources.discover_spread_summary
import space.opensensor.discover.resources.discover_stale
import space.opensensor.discover.resources.discover_steps_info1
import space.opensensor.discover.resources.discover_steps_title
import space.opensensor.discover.resources.discover_time_window
import space.opensensor.discover.resources.discover_unavailable
import space.opensensor.discover.resources.discover_updated_hours_ago
import space.opensensor.discover.resources.discover_updated_recently
import space.opensensor.discover.resources.discover_who_caption_compared
import space.opensensor.discover.resources.discover_who_caption_day
import space.opensensor.discover.resources.discover_who_info1
import space.opensensor.discover.resources.discover_who_info2
import space.opensensor.discover.resources.discover_who_info3
import space.opensensor.discover.resources.discover_who_mark
import space.opensensor.discover.resources.discover_who_step_spoken
import space.opensensor.discover.resources.discover_who_title
import space.opensensor.discover.resources.discover_window_24_hours
import space.opensensor.discover.resources.discover_window_7_days
import space.opensensor.discover.resources.discover_window_last_hour
import space.opensensor.discover.resources.discover_window_latest_day
import space.opensensor.discover.resources.discover_window_short_day
import space.opensensor.discover.resources.discover_window_short_hour
import space.opensensor.discover.resources.discover_window_short_latest_day
import space.opensensor.discover.resources.discover_window_short_week
import space.opensensor.publicfeed.StepScale

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
    /** The DAY window of a daily feed, its newest day of data. */
    val windowLatestDay: String,
    val sampleData: String,
    val sampleInfo: InfoText,
    val scaleInfo: InfoText,
    /** [scaleInfo] for the daily public feed, one or more things, the latest day. */
    val scaleInfoReal: InfoText,
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
    /** A cell's window read that failed after its retries, with [retry] beside it. */
    val readFailed: String,
    val retry: String,
    val noPlaces: String,
    val clearSearch: String,
    val updatedRecently: String,
    /** Short labels beside the time slider stops. The full window names are spoken. */
    val windowShortHour: String,
    val windowShortDay: String,
    val windowShortWeek: String,
    val windowShortLatestDay: String,
    /** The time pill, spoken only. Its state is the full window name. */
    val timeWindow: String,
    /** The phone's language, for step names a scale publishes ([stepName]). */
    val language: String,
    /** The short mark at the guideline on the legend and the charts. */
    val whoMark: String,
    /** [whoMark] for a published scale that is not WHO's. */
    val scaleMark: String,
    /** Legend caption and info title for a published scale that is not WHO's. */
    val stepsTitle: String,
    /** The legend info sheet for a published scale that is not WHO's. */
    val stepsInfo: InfoText,
    /** [stepsInfo] for the daily public feed. */
    val stepsInfoReal: InfoText,
    /** Legend caption in the 24 hour window, where the means match the guideline's averaging. */
    val whoCaptionDay: String,
    /** Legend caption in the last hour and 7 day windows. */
    val whoCaptionCompared: String,
    /** Legend caption for a property without a published health scale. */
    val noHealthScale: String,
    /** The legend info sheet for PM2.5 and PM10. */
    val whoInfo: InfoText,
    /** [whoInfo] for the daily public feed. */
    val whoInfoReal: InfoText,

    /** The legend info sheet at the Simple level. No guideline body, no numbers. */
    val moodInfo: InfoText,
    /** Joins two ends of a range, as in "15 to 25". */
    val to: String,
    /** The map's spoken name, followed by the chosen measurement. */
    val mapLabel: String,
    /** The Sample and Real toggle, its spoken name and its two choices. */
    val sourceLabel: String,
    val sourceSample: String,
    val sourceReal: String,
    /** Spoken on the toggle while public sensors is off. */
    val sourceOff: String,
    /** The Real mode attribution, opening the OpenAQ licenses. */
    val dataOpenAq: String,
    val realInfo: InfoText,
    /** The real feed could not be read, with [retry] beside it. */
    val loadFailed: String,
    /** Short month names, January first, for [dayMonth]. */
    val monthsShort: List<String>,
    /** The sheet handle, spoken only. It says what a tap does next. */
    val sheetExpand: String,
    val sheetCollapse: String,
    /** Lets go of a pinned chart bar. */
    val clearFilter: String,
    /** The sheet's list of the user's own fixed Things, the list alternative to their map pins. */
    val ownHeading: String,
    /** An own Thing with no name. */
    val ownUnnamed: String,
    /** An own Thing the phone is linked to now. */
    val live: String,
    /** On a public area that folds in a sensor the user shares. */
    val includesOwn: String,
) {
    /** "12 min ago", the age of an own reading or of the kept public data. */
    @Composable
    internal fun age(step: AgeStep, n: Long): String = when (step) {
        AgeStep.JUST_NOW -> stringResource(Res.string.discover_ago_just_now)
        AgeStep.MINUTES -> stringResource(Res.string.discover_ago_minutes, n.toInt())
        AgeStep.HOURS -> stringResource(Res.string.discover_ago_hours, n.toInt())
        AgeStep.DAYS -> stringResource(Res.string.discover_ago_days, n.toInt())
    }

    /** The Real label while the kept data is on screen, as in "OpenAQ, cached 2 h ago". */
    @Composable
    fun realCached(age: String): String = stringResource(Res.string.discover_real_cached, age)

    /** The sources line of the own sensors, with the day of their newest reading on this phone. */
    @Composable
    fun sourcesOwn(newest: LocalDate?): String =
        if (newest == null) stringResource(Res.string.discover_sources_own_none) else stringResource(Res.string.discover_sources_own, dayMonth(newest))

    /** The sources line of the public feed, with its data day and, when kept, the age of the copy. */
    @Composable
    fun sourcesPublic(dataTo: LocalDate, cachedAge: String?): String = if (cachedAge == null) {
        stringResource(Res.string.discover_sources_public, dayMonth(dataTo))
    } else {
        stringResource(Res.string.discover_sources_public_cached, dayMonth(dataTo), cachedAge)
    }

    /** The sources line of the bundled sample, with the day its data runs to. */
    @Composable
    fun sourcesSample(dataTo: LocalDate): String = stringResource(Res.string.discover_sources_sample, dayMonth(dataTo))

    /** Spoken on an own Thing row. A tap moves the map to it. */
    @Composable
    fun ownShow(name: String): String = stringResource(Res.string.discover_own_show, name)

    /** The Real label, as in "OpenAQ, data to 8 Oct". */
    @Composable
    fun realDataTo(date: LocalDate): String = stringResource(Res.string.discover_real_data_to, dayMonth(date))

    /** "Data to 8 Oct", the last day a daily value measured. */
    @Composable
    fun dataTo(date: LocalDate): String = stringResource(Res.string.discover_data_to, dayMonth(date))

    /** A short local date, day then month. */
    @Composable
    fun dayMonth(date: LocalDate): String =
        stringResource(Res.string.discover_day_month, date.day, monthsShort.getOrElse(date.month.ordinal) { date.month.name })

    /** The name of step [i] of [scale] in [language], so colour is never the only cue. */
    fun stepName(scale: StepScale, i: Int): String = scale.label(i, language)

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

/** The lines of Discover's map info, the basemap credits and the GeoNames credit of the place search. */
internal val DiscoverMapInfo: List<StringResource> = listOf(Res.string.discover_map_info1, Res.string.discover_map_info2, Res.string.discover_map_info3)

/** Discover's copy in the phone's language, from the shared Compose resources. */
@Composable
fun discoverStrings(): DiscoverStrings = DiscoverStrings(
    sourceLabel = stringResource(Res.string.discover_source_label),
    sourceSample = stringResource(Res.string.discover_source_sample),
    sourceReal = stringResource(Res.string.discover_source_real),
    sourceOff = stringResource(Res.string.discover_source_off),
    dataOpenAq = stringResource(Res.string.discover_data_openaq),
    realInfo = InfoText(stringResource(Res.string.discover_real_title), listOf(stringResource(Res.string.discover_real_info))),
    loadFailed = stringResource(Res.string.discover_load_failed),
    monthsShort = stringArrayResource(Res.array.discover_months_short),
    sheetExpand = stringResource(Res.string.discover_sheet_expand),
    sheetCollapse = stringResource(Res.string.discover_sheet_collapse),
    clearFilter = stringResource(Res.string.discover_clear_filter),
    ownHeading = stringResource(Res.string.discover_own_heading),
    ownUnnamed = stringResource(Res.string.discover_own_unnamed),
    live = stringResource(Res.string.discover_live),
    includesOwn = stringResource(Res.string.discover_includes_own),
    searchPlaces = stringResource(Res.string.discover_search_places),
    addSensor = stringResource(Res.string.discover_add_sensor),
    more = stringResource(Res.string.discover_more),
    windowLastHour = stringResource(Res.string.discover_window_last_hour),
    window24Hours = stringResource(Res.string.discover_window_24_hours),
    window7Days = stringResource(Res.string.discover_window_7_days),
    windowLatestDay = stringResource(Res.string.discover_window_latest_day),
    sampleData = stringResource(Res.string.discover_sample_data),
    sampleInfo = InfoText(stringResource(Res.string.discover_sample_data), listOf(stringResource(Res.string.discover_sample_info))),
    scaleInfo = InfoText(
        stringResource(Res.string.discover_scale_title),
        listOf(stringResource(Res.string.discover_scale_info1), stringResource(Res.string.discover_scale_info2)),
    ),
    scaleInfoReal = InfoText(
        stringResource(Res.string.discover_scale_title),
        listOf(stringResource(Res.string.discover_scale_info1), stringResource(Res.string.discover_scale_info2_real)),
    ),
    osm = stringResource(Res.string.discover_osm),
    mapInfo = InfoText(
        stringResource(Res.string.discover_map_title),
        DiscoverMapInfo.map { stringResource(it) },
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
    readFailed = stringResource(Res.string.discover_read_failed),
    retry = stringResource(Res.string.discover_retry),
    noPlaces = stringResource(Res.string.discover_no_places),
    clearSearch = stringResource(Res.string.discover_clear_search),
    updatedRecently = stringResource(Res.string.discover_updated_recently),
    windowShortHour = stringResource(Res.string.discover_window_short_hour),
    windowShortDay = stringResource(Res.string.discover_window_short_day),
    windowShortWeek = stringResource(Res.string.discover_window_short_week),
    windowShortLatestDay = stringResource(Res.string.discover_window_short_latest_day),
    timeWindow = stringResource(Res.string.discover_time_window),
    language = preferredLanguage(),
    whoMark = stringResource(Res.string.discover_who_mark),
    scaleMark = stringResource(Res.string.discover_scale_mark),
    stepsTitle = stringResource(Res.string.discover_steps_title),
    stepsInfo = InfoText(
        stringResource(Res.string.discover_steps_title),
        listOf(stringResource(Res.string.discover_steps_info1), stringResource(Res.string.discover_scale_info2)),
    ),
    stepsInfoReal = InfoText(
        stringResource(Res.string.discover_steps_title),
        listOf(stringResource(Res.string.discover_steps_info1), stringResource(Res.string.discover_scale_info2_real)),
    ),
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
    whoInfoReal = InfoText(
        stringResource(Res.string.discover_who_title),
        listOf(
            stringResource(Res.string.discover_who_info1),
            stringResource(Res.string.discover_who_info2),
            stringResource(Res.string.discover_who_info3),
            stringResource(Res.string.discover_scale_info2_real),
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
