package space.opensensor.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.history.CellTrackReader
import space.opensensor.data.history.HistoryRepository
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.settings.AppSettings
import space.opensensor.data.settings.DetailLevel
import space.opensensor.data.settings.DiscoverStore
import space.opensensor.data.sync.ThingPlaces
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.discover.DiscoverController
import space.opensensor.discover.DiscoverScreen
import space.opensensor.discover.OWN_TRACK_SPAN_NS
import space.opensensor.discover.OwnThing
import space.opensensor.discover.bundledSampleFeed
import space.opensensor.discover.knownOwnThings
import space.opensensor.discover.observeOwnStatus
import space.opensensor.discover.observeOwnTracks
import space.opensensor.net.BlockCache
import space.opensensor.net.OkHttpRangeEngine
import space.opensensor.publicfeed.OpenAqAggregatesFeed
import space.opensensor.publicfeed.platformH3Grid
import space.opensensor.settings.DiscoverSource
import space.opensensor.ui.components.LocalUnits
import java.util.TimeZone
import javax.inject.Inject

/** Owns the shared [DiscoverController] for the Discover entry, so it survives rotation. */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    store: DiscoverStore,
    settings: AppSettings,
    blocks: BlockCache,
    places: ThingPlaces,
    known: KnownDeviceDao,
    history: HistoryRepository,
    connections: DeviceConnectionManager,
    index: ArchiveIndex,
    archive: ArchiveStore,
) : ViewModel() {
    val controller = DiscoverController(
        scope = viewModelScope,
        feed = bundledSampleFeed(),
        h3 = platformH3Grid(),
        prefs = store,
        timeZoneId = TimeZone.getDefault().id,
        now = System::currentTimeMillis,
        // Read only while public sensors is on and Real is chosen. Anonymous GETs, no identifiers.
        liveFeed = OpenAqAggregatesFeed(OkHttpRangeEngine(publicFeedClient), blocks = blocks),
        saveSource = settings::setDiscoverSource,
    )

    init {
        // The controller outlives the screen, so the switch reaches a read in flight even while
        // Settings is open. Turning public sensors off cancels it before another request leaves.
        combine(settings.discoverSource, settings.showPublicSensors, ::Pair)
            .onEach { (source, publicSensors) -> controller.onSourceSettings(source, publicSensors) }
            .launchIn(viewModelScope)
        // The user's own Things, each at its saved cell (#226). Read from the phone only.
        combine(places.observeAll(), known.observeAll()) { saved, devices ->
            val names = devices.mapNotNull { d -> d.thing?.let { it to d.name } }.toMap()
            knownOwnThings(saved.map { OwnThing(it.thing, "", it.cell, it.updatedEpochMs, it.moving) }, names)
        }
            .onEach(controller::showOwnThings)
            .launchIn(viewModelScope)
        // A moving Thing's last day of rows, its own cell per row, read from this phone (#263).
        val tracks = CellTrackReader(archive, index, Dispatchers.IO)
        val moving = places.observeAll().map { saved -> saved.filter { it.moving }.mapTo(mutableSetOf()) { it.thing } }
        observeOwnTracks(moving) { thing -> tracks.observe(thing, OWN_TRACK_SPAN_NS) { System.currentTimeMillis() * 1_000_000 } }
            .onEach(controller::showOwnTracks)
            .launchIn(viewModelScope)
        // Each own Thing's newest reading from this phone's history, Live while a BLE or LAN link
        // is ready, and whether a file of it went up (#273). Read from the phone only.
        val linked = combine(connections.snapshots, known.observeAll()) { snapshots, devices ->
            val ready = snapshots.filter { it.connection is ConnectionState.Ready }.mapTo(mutableSetOf()) { it.address }
            devices.filter { it.address in ready }.mapNotNullTo(mutableSetOf()) { it.thing }
        }.distinctUntilChanged()
        val things = places.observeAll().map { saved -> saved.mapTo(mutableSetOf()) { it.thing } }
        observeOwnStatus(history.tiers, things, linked, index.observeUploadedThings())
            .onEach(controller::showOwnStatus)
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
