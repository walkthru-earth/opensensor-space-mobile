package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.CellFreshness
import earth.walkthru.aqsync.publicfeed.CellGeoJson
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellScale
import earth.walkthru.aqsync.publicfeed.FeedPartition
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.GeoBounds
import earth.walkthru.aqsync.publicfeed.H3Grid
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.LatLng
import earth.walkthru.aqsync.publicfeed.PropertyRanking
import earth.walkthru.aqsync.publicfeed.PublicFeed
import earth.walkthru.aqsync.publicfeed.TimeZoneRegions
import earth.walkthru.aqsync.publicfeed.ViewportCover
import earth.walkthru.aqsync.storage.MemoryTrimmable
import earth.walkthru.aqsync.storage.trimOnMemoryPressure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A camera worth restoring: where the map was last left. */
data class SavedCamera(val lat: Double, val lng: Double, val zoom: Double)

/** What Discover remembers between launches. Never a location fix, only the map camera. */
data class DiscoverSaved(
    val camera: SavedCamera? = null,
    val property: String? = null,
    val window: FeedWindow = FeedWindow.DAY,
    val firstRunDone: Boolean = false,
)

/** Persistence for [DiscoverSaved]. Android uses DataStore, iOS uses NSUserDefaults. */
interface DiscoverPrefs {
    suspend fun load(): DiscoverSaved

    suspend fun save(saved: DiscoverSaved)
}

/** Where the map opens. A saved camera wins, then the time zone region. */
sealed interface StartView {
    data class Camera(val camera: SavedCamera) : StartView

    data class Region(val bounds: GeoBounds) : StartView
}

/** The camera after it rested for 400 ms. */
data class CameraRest(val bounds: GeoBounds, val zoom: Double, val lat: Double, val lng: Double)

/** Everything the Discover screen draws. */
data class DiscoverUiState(
    val start: StartView? = null,
    /** False on a platform that cannot read the feed or compute H3 yet (iOS until W8). */
    val available: Boolean = true,
    val loading: Boolean = true,
    val isSample: Boolean = true,
    val window: FeedWindow = FeedWindow.DAY,
    val dictionary: List<FeedProperty> = emptyList(),
    /** Properties reported in view, most reported first. */
    val ranked: List<FeedProperty> = emptyList(),
    val chips: List<FeedProperty> = emptyList(),
    val property: FeedProperty? = null,
    /** WHO steps for PM2.5 and PM10, the percentile ramp for the rest. */
    val scale: CellScale? = null,
    val rows: List<CellRow> = emptyList(),
    val geoJson: String = EMPTY_GEOJSON,
    val selected: CellRow? = null,
    val newestUpdateEpochMs: Long? = null,
    val firstRun: Boolean = false,
    val res: Int = ViewportCover.MIN_RES,
) {
    companion object {
        const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}

/**
 * State holder of the Discover screen. Android keeps it in a ViewModel, iOS in the composition.
 * Reads run on [io], never on the main thread, and only after the camera rests for 400 ms.
 */
class DiscoverController(
    private val scope: CoroutineScope,
    private val feed: PublicFeed,
    private val h3: H3Grid,
    private val prefs: DiscoverPrefs,
    private val timeZoneId: String,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(DiscoverUiState(available = feed.available && h3.available, isSample = feed.isSample))
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val rest = MutableStateFlow<CameraRest?>(null)
    private val reload = MutableStateFlow(0)
    private var saved = DiscoverSaved()
    private var partition: FeedPartition? = null
    private var restyleJob: Job? = null

    init {
        // A feed that caches bytes in memory gives them back under memory pressure (#61).
        if (feed is MemoryTrimmable) scope.trimOnMemoryPressure(feed)
        scope.launch {
            saved = runCatching { prefs.load() }.getOrDefault(DiscoverSaved())
            val start = saved.camera?.let { StartView.Camera(it) } ?: StartView.Region(TimeZoneRegions.regionFor(timeZoneId))
            _state.update { it.copy(start = start, window = saved.window, firstRun = !saved.firstRunDone) }
            partition = withContext(io) { runCatching { feed.latest() }.getOrNull() }
            // One loader. A newer camera rest or a chip change cancels the read in flight.
            combine(rest.filterNotNull(), reload) { r, _ -> r }.collectLatest { r ->
                delay(REST_MS)
                load(r)
            }
        }
    }

    /** Called on every camera move. The read starts once the camera has rested for 400 ms. */
    fun onCamera(rest: CameraRest) {
        this.rest.value = rest
    }

    /** The coarse cell centre "Near me" moves the camera to, so the saved camera never holds the fix. */
    fun nearMeTarget(lat: Double, lng: Double): LatLng? = nearMeTarget(h3, lat, lng)

    fun selectProperty(id: String) {
        if (_state.value.property?.id == id) return
        saved = saved.copy(property = id)
        persist()
        restyle(id)
    }

    fun selectWindow(next: FeedWindow) {
        if (_state.value.window == next) return
        saved = saved.copy(window = next)
        persist()
        _state.update { it.copy(window = next, loading = true) }
        reload.value++
    }

    fun selectCell(hex: String?) {
        val row = hex?.let { h -> _state.value.rows.firstOrNull { H3Index.toHex(it.h3) == h } }
        if (row?.h3 == _state.value.selected?.h3) return
        // The selected outline is a filter on its own layer, so the GeoJSON is not rebuilt.
        _state.update { it.copy(selected = row) }
    }

    fun dismissFirstRun() {
        if (!_state.value.firstRun) return
        saved = saved.copy(firstRunDone = true)
        persist()
        _state.update { it.copy(firstRun = false) }
    }

    private fun persist() {
        val snapshot = saved
        scope.launch { runCatching { prefs.save(snapshot) } }
    }

    private suspend fun load(r: CameraRest) {
        saved = saved.copy(camera = SavedCamera(r.lat, r.lng, r.zoom))
        persist()
        val res = ViewportCover.resolutionForZoom(r.zoom)
        val current = _state.value
        val p = partition
        if (!current.available || p == null) {
            _state.update { it.copy(loading = false, res = res) }
            return
        }
        val window = current.window
        val table = withContext(io) {
            runCatching {
                val cover = ViewportCover.cover(h3, r.bounds, res)
                feed.cells(p, window, res, cover.ranges)
            }.getOrNull()
        }
        val rows = table?.rows.orEmpty()
        val dictionary = table?.info?.properties.orEmpty().ifEmpty { current.dictionary }
        val ranked = PropertyRanking.rank(rows, dictionary)
        val wanted = current.property?.id ?: saved.property
        val property = ranked.firstOrNull { it.id == wanted } ?: dictionary.firstOrNull { it.id == wanted && ranked.isEmpty() } ?: ranked.firstOrNull()
        val selected = current.selected?.let { s -> rows.firstOrNull { it.h3 == s.h3 } }
        _state.update {
            it.copy(
                loading = false,
                isSample = table?.info?.sample ?: feed.isSample,
                dictionary = dictionary,
                ranked = ranked,
                rows = rows,
                selected = selected,
                res = res,
                newestUpdateEpochMs = rows.maxOfOrNull { row -> row.updatedEpochMs },
            )
        }
        restyle(property?.id)
    }

    /** Recomputes chips, scale and GeoJSON for [propertyId] from the rows already loaded. */
    private fun restyle(propertyId: String?) {
        val current = _state.value
        val property = current.ranked.firstOrNull { it.id == propertyId } ?: current.dictionary.firstOrNull { it.id == propertyId }
        val rows = current.rows
        restyleJob?.cancel()
        restyleJob = scope.launch {
            // Sample data is a snapshot, so freshness is read against its own newest hour.
            val nowMs = if (current.isSample) rows.maxOfOrNull { it.updatedEpochMs } ?: now() else now()
            val (scale, json) = withContext(io) {
                val scale = property?.let { p -> CellScale.of(p.id, rows.mapNotNull { it.mean(p.id) }) }
                val json = if (current.available) {
                    CellGeoJson.build(h3, rows, property?.id, scale) { CellFreshness.isStale(it, nowMs) }
                } else {
                    DiscoverUiState.EMPTY_GEOJSON
                }
                scale to json
            }
            _state.update {
                // A newer load may have replaced the rows meanwhile. Keep its result.
                if (it.rows !== rows) return@update it
                it.copy(
                    property = property,
                    chips = PropertyRanking.chips(it.ranked, property?.id),
                    scale = scale,
                    geoJson = json,
                )
            }
        }
    }

    private companion object {
        const val REST_MS = 400L
    }
}
