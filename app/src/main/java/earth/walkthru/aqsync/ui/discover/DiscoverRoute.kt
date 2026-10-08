package earth.walkthru.aqsync.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import earth.walkthru.aqsync.ui.settings.SettingsSheet
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
 * Discover, the start tab when no device is paired. The map is shared Compose
 * (`:feature:discover`). This route supplies Android strings, the app's info button and the
 * bottom tabs (Discover, My devices, Settings).
 */
@Composable
fun DiscoverRoute(onAddSensor: () -> Unit, onMyDevices: () -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val showPublic by viewModel.showPublicSensors.collectAsStateWithLifecycle()
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Back first returns the sheet to peek, then leaves the screen.
    BackHandler(enabled = state.selected != null) { viewModel.controller.selectCell(null) }

    Column(Modifier.fillMaxSize()) {
        DiscoverScreen(
            controller = viewModel.controller,
            strings = discoverStrings(),
            networkAllowed = showPublic,
            onAddSensor = onAddSensor,
            modifier = Modifier.weight(1f),
            infoButton = { info, modifier -> InfoButton(InfoContent(info.title, info.body), modifier) },
        )
        NavigationBar {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Outlined.Map, contentDescription = null) },
                label = { Text(stringResource(R.string.tab_discover)) },
            )
            NavigationBarItem(
                selected = false,
                onClick = onMyDevices,
                icon = { Icon(Icons.Outlined.Sensors, contentDescription = null) },
                label = { Text(stringResource(R.string.tab_my_devices)) },
            )
            NavigationBarItem(
                selected = false,
                onClick = { showSettings = true },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.settings_title)) },
            )
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
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
    )
}
