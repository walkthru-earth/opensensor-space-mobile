package earth.walkthru.aqsync.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DiscoverStore
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
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

    /** Starts false, so no request leaves the phone before the saved switch is read. */
    val showPublicSensors: StateFlow<Boolean> = settings.showPublicSensors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * Discover, always the start tab. The map and its copy are shared Compose (`:feature:discover`).
 * This route supplies the app's info button. The bottom bar belongs to the app root
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
        networkAllowed = showPublic,
        onAddSensor = onAddSensor,
        modifier = Modifier.fillMaxSize(),
        infoButton = { info, modifier -> InfoButton(InfoContent(info.title, info.body), modifier) },
        units = LocalUnits.current.system,
    )
}
