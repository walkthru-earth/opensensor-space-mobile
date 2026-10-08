package earth.walkthru.aqsync.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DiscoverStore
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.DiscoverStrings
import earth.walkthru.opensensor.discover.InfoText
import earth.walkthru.opensensor.discover.bundledSampleFeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.TimeZone
import javax.inject.Inject

/** Owns the shared [DiscoverController] for the Discover entry, so it survives rotation. */
@HiltViewModel
class DiscoverViewModel @Inject constructor(store: DiscoverStore, settings: AppSettings) : ViewModel() {
    val controller = DiscoverController(
        scope = viewModelScope,
        feed = bundledSampleFeed(),
        h3 = platformH3Grid(),
        prefs = store,
        timeZoneId = TimeZone.getDefault().id,
        now = System::currentTimeMillis,
    )

    val showPublicSensors: StateFlow<Boolean> = settings.showPublicSensors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}

/**
 * Discover, always the start tab. The map is shared Compose (`:feature:discover`). This route
 * supplies Android strings and the app's info button. The bottom bar belongs to the app root
 * (`Navigation.kt`).
 */
@Composable
fun DiscoverRoute(onAddSensor: () -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val showPublic by viewModel.showPublicSensors.collectAsStateWithLifecycle()
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    // Back first returns the sheet to peek, then leaves the screen.
    BackHandler(enabled = state.selected != null) { viewModel.controller.selectCell(null) }

    DiscoverScreen(
        controller = viewModel.controller,
        strings = discoverStrings(),
        networkAllowed = showPublic,
        onAddSensor = onAddSensor,
        modifier = Modifier.fillMaxSize(),
        infoButton = { info, modifier -> InfoButton(InfoContent(info.title, info.body), modifier) },
        units = LocalUnits.current.system,
    )
}

@Composable
private fun discoverStrings(): DiscoverStrings {
    val res = LocalResources.current
    return DiscoverStrings(
        searchPlaces = stringResource(R.string.discover_search_places),
        addSensor = stringResource(R.string.discover_add_sensor),
        more = stringResource(R.string.discover_more),
        windowLastHour = stringResource(R.string.discover_window_last_hour),
        window24Hours = stringResource(R.string.discover_window_24_hours),
        window7Days = stringResource(R.string.discover_window_7_days),
        sampleData = stringResource(R.string.discover_sample_data),
        sampleInfo = InfoText(stringResource(R.string.discover_sample_data), listOf(stringResource(R.string.discover_sample_info))),
        scaleInfo = InfoText(stringResource(R.string.discover_scale_title), listOf(stringResource(R.string.discover_scale_info1), stringResource(R.string.discover_scale_info2))),
        osm = stringResource(R.string.discover_osm),
        mapInfo = InfoText(stringResource(R.string.discover_map_title), listOf(stringResource(R.string.discover_map_info1), stringResource(R.string.discover_map_info2))),
        noAreas = stringResource(R.string.discover_no_areas),
        notMeasured = stringResource(R.string.discover_not_measured),
        stale = stringResource(R.string.discover_stale),
        avg = stringResource(R.string.discover_avg),
        min = stringResource(R.string.discover_min),
        max = stringResource(R.string.discover_max),
        firstRunLine = stringResource(R.string.discover_first_run),
        explore = stringResource(R.string.discover_explore),
        allMeasurements = stringResource(R.string.discover_all_measurements),
        unavailable = stringResource(R.string.discover_unavailable),
        loading = stringResource(R.string.discover_loading),
        noPlaces = stringResource(R.string.discover_no_places),
        clearSearch = stringResource(R.string.discover_clear_search),
        areasInView = { n -> res.getQuantityString(R.plurals.discover_areas_in_view, n, n) },
        sensors = { n -> res.getQuantityString(R.plurals.discover_sensors, n, n) },
        updatedHoursAgo = { n -> res.getQuantityString(R.plurals.discover_updated_hours_ago, n, n) },
        updatedRecently = stringResource(R.string.discover_updated_recently),
        nearMe = stringResource(R.string.discover_near_me),
        windowShortHour = stringResource(R.string.discover_window_short_hour),
        windowShortDay = stringResource(R.string.discover_window_short_day),
        windowShortWeek = stringResource(R.string.discover_window_short_week),
        timeWindow = stringResource(R.string.discover_time_window),
        higherThan = { n -> res.getString(R.string.discover_higher_than, n) },
        rangeSummary = { label, avg, min, max, unit ->
            if (min != null && max != null) {
                res.getString(R.string.discover_range_summary, label, avg, min, max, unit)
            } else {
                res.getString(R.string.discover_average_summary, label, avg, unit)
            }
        },
        spreadSummary = { label, n, low, high, unit -> res.getQuantityString(R.plurals.discover_spread_summary, n, label, n, low, high, unit) },
        whoSteps = listOf(
            stringResource(R.string.who_step_guideline),
            stringResource(R.string.who_step_it4),
            stringResource(R.string.who_step_it3),
            stringResource(R.string.who_step_it2),
            stringResource(R.string.who_step_it1),
            stringResource(R.string.who_step_above_it1),
        ),
        whoMark = stringResource(R.string.discover_who_mark),
        whoCaptionDay = stringResource(R.string.discover_who_caption_day),
        whoCaptionCompared = stringResource(R.string.discover_who_caption_compared),
        noHealthScale = stringResource(R.string.discover_no_health_scale),
        whoInfo = InfoText(
            stringResource(R.string.discover_who_title),
            listOf(
                stringResource(R.string.discover_who_info1),
                stringResource(R.string.discover_who_info2),
                stringResource(R.string.discover_who_info3),
                stringResource(R.string.discover_scale_info2),
            ),
        ),
        offScale = { v -> res.getString(R.string.discover_off_scale, v) },
        to = res.getString(R.string.discover_range_to),
        areaCount = { n -> res.getQuantityString(R.plurals.discover_area_count, n, n) },
        whoStepSpoken = { name, upTo, unit -> if (upTo != null) res.getString(R.string.discover_who_step_spoken, name, upTo, unit) else name },
    )
}
