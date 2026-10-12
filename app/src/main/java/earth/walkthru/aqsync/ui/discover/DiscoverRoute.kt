package earth.walkthru.aqsync.ui.discover

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
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.settings.DiscoverStore
import earth.walkthru.aqsync.net.OkHttpRangeEngine
import earth.walkthru.aqsync.publicfeed.OpenAqAggregatesFeed
import earth.walkthru.aqsync.publicfeed.platformH3Grid
import earth.walkthru.aqsync.settings.DiscoverSource
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.opensensor.designsystem.InfoButton
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.discover.DiscoverController
import earth.walkthru.opensensor.discover.DiscoverScreen
import earth.walkthru.opensensor.discover.bundledSampleFeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
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
        // Read only while public sensors is on and Real is chosen. Anonymous GETs, no identifiers.
        liveFeed = OpenAqAggregatesFeed(OkHttpRangeEngine(publicFeedClient)),
        saveSource = settings::setDiscoverSource,
    )

    init {
        // The controller outlives the screen, so the switch reaches a read in flight even while
        // Settings is open. Turning public sensors off cancels it before another request leaves.
        combine(settings.discoverSource, settings.showPublicSensors, ::Pair)
            .onEach { (source, publicSensors) -> controller.onSourceSettings(source, publicSensors) }
            .launchIn(viewModelScope)
    }

    /** The saved Discover source. Sample until it is read, so nothing is fetched before then. */
    val source: StateFlow<DiscoverSource> = settings.discoverSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverSource.SAMPLE)

    /** Starts false, so no request leaves the phone before the saved switch is read. */
    val showPublicSensors: StateFlow<Boolean> = settings.showPublicSensors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Simple until the saved level is read, so nothing more technical flashes first. */
    val level: StateFlow<DetailLevel> = settings.detailLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLevel.SIMPLE)
}

/**
 * Discover, always the start tab. The map and its copy are shared Compose (`:feature:discover`).
 * This route supplies the app's info button. The shared screen takes Back first while a sheet is open. The bottom bar belongs to the shared shell
 * (`:feature:shell`).
 */
@Composable
fun DiscoverRoute(onAddSensor: () -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val showPublic by viewModel.showPublicSensors.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    DiscoverScreen(
        controller = viewModel.controller,
        networkAllowed = showPublic,
        level = level,
        onAddSensor = onAddSensor,
        modifier = Modifier.fillMaxSize(),
        infoButton = { info, modifier -> InfoButton(InfoContent(info.title, info.body), modifier) },
        units = LocalUnits.current.system,
        savedSource = source,
    )
}

/** One HTTP client for the public feed in the process, so connections are reused across entries. */
private val publicFeedClient: OkHttpClient by lazy { OkHttpRangeEngine.newClient() }
