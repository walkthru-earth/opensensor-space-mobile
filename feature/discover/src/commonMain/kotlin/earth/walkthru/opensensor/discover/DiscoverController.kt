package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.StationSummary
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
import earth.walkthru.aqsync.publicfeed.PropertyRanking
import earth.walkthru.aqsync.publicfeed.PublicFeed
import earth.walkthru.aqsync.publicfeed.PublicSummary
import earth.walkthru.aqsync.publicfeed.TimeZoneRegions
import earth.walkthru.aqsync.publicfeed.ViewportCover
import earth.walkthru.aqsync.storage.MemoryTrimmable
import earth.walkthru.aqsync.storage.trimOnMemoryPressure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /**
     * The selected cell in each window that has been read. The current window comes with the
     * selection, the other two follow from one read each, for that one cell.
     */
    val selectedWindows: Map<FeedWindow, CellRow?> = emptyMap(),
    /**
     * Windows of the selected cell whose read failed after its retries. Never in [selectedWindows].
     * Cleared by a new cell, window or resolution, and by a later read that succeeds.
     */
    val failedWindows: Set<FeedWindow> = emptySet(),
    val newestUpdateEpochMs: Long? = null,
    val firstRun: Boolean = false,
    val res: Int = ViewportCover.MIN_RES,
) {
    companion object {
        const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}

/** The selected cell's summary for [property], three windows, headline the 24 hour mean. Null with no selection. */
fun DiscoverUiState.selectedSummary(property: FeedProperty): StationSummary? {
    selected ?: return null
    return PublicSummary.of(property.id, property.unit, selectedWindows)
}

/**
 * What the Simple cell sheet shows under the headline, all from the 24 hour row like the headline.
 * The row of the window on screen can still be the previous window's during a switch, so it is
 * never read for values. Null [devices] and [updatedEpochMs] until the 24 hour row lands.
 */
internal data class SimpleCellFacts(
    val devices: Int?,
    val updatedEpochMs: Long?,
    val others: List<Pair<FeedProperty, Double?>>,
    /** True until the 24 hour row is read. The sheet then shows a neutral loading line, no values. */
    val loading: Boolean,
)

/** What the Simple cell sheet leads with. */
internal enum class SimpleHeadline {
    /** The 24 hour row is not read yet. No mood, no "No reading", no "Not measured". */
    LOADING,

    /** A read failed after its retries, the 24 hour one or one that could still hold the property. */
    FAILED,

    /** Read, and no window read so far reports the property. */
    NOT_MEASURED,

    /** Read. The headline shows the 24 hour mean, or "n/a" when that row lacks it. */
    SHOWN,
}

/**
 * A window key that is absent was not read yet, or is in [DiscoverUiState.failedWindows] when its
 * read failed after the retries. A key that is present was read, its row null when the cell has
 * nothing in that window. A failure is never taken as "not measured".
 */
internal fun DiscoverUiState.simpleHeadline(): SimpleHeadline {
    val p = property ?: return SimpleHeadline.NOT_MEASURED
    if (selected == null) return SimpleHeadline.NOT_MEASURED
    if (FeedWindow.DAY !in selectedWindows) {
        return if (FeedWindow.DAY in failedWindows) SimpleHeadline.FAILED else SimpleHeadline.LOADING
    }
    val measured = selectedWindows.values.any { it?.stats?.containsKey(p.id) == true }
    return when {
        measured -> SimpleHeadline.SHOWN
        failedWindows.isNotEmpty() -> SimpleHeadline.FAILED
        else -> SimpleHeadline.NOT_MEASURED
    }
}

/**
 * The selected cell's 24 hour facts. [SimpleCellFacts.others] lists every other measurement the
 * cell reports, its 24 hour mean or null ("n/a") while that row is missing or lacks it.
 */
internal fun DiscoverUiState.simpleCellFacts(): SimpleCellFacts {
    if (selected != null && FeedWindow.DAY !in selectedWindows) return SimpleCellFacts(null, null, emptyList(), loading = true)
    val day = if (selected == null) null else selectedWindows[FeedWindow.DAY]
    val others = dictionary
        .filter { p -> p.id != property?.id && (day?.mean(p.id) != null || selected?.mean(p.id) != null) }
        .map { p -> p to day?.mean(p.id) }
    return SimpleCellFacts(day?.devices, day?.updatedEpochMs, others, loading = false)
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
    private var windowsJob: Job? = null

    /** The cell, window and resolution [windowsJob] read for, so a pan does not read them again. */
    private var windowsFor: Triple<Long, FeedWindow, Int>? = null

    /** The window the rows on screen were read for. It lags `state.window` until the reload lands. */
    private var rowsWindow: FeedWindow? = null

    init {
        // A feed that caches bytes in memory gives them back under memory pressure (#61).
        if (feed is MemoryTrimmable) scope.trimOnMemoryPressure(feed)
        scope.launch {
            saved = catching { prefs.load() }.getOrDefault(DiscoverSaved())
            val start = saved.camera?.let { StartView.Camera(it) } ?: StartView.Region(TimeZoneRegions.regionFor(timeZoneId))
            _state.update { it.copy(start = start, window = saved.window, firstRun = !saved.firstRunDone) }
            partition = withContext(io) { catching { feed.latest() }.getOrNull() }
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
        _state.update { it.copy(window = next, loading = true, failedWindows = emptySet()) }
        reload.value++
    }

    fun selectCell(hex: String?) {
        val row = hex?.let { h -> _state.value.rows.firstOrNull { H3Index.toHex(it.h3) == h } }
        if (row?.h3 == _state.value.selected?.h3) return
        // Rows still from the previous window are not this window's values. The reload fills them.
        val current = row != null && rowsWindow == _state.value.window
        // The selected outline is a filter on its own layer, so the GeoJSON is not rebuilt.
        _state.update { s -> s.copy(selected = row, selectedWindows = if (row != null && current) mapOf(s.window to row) else emptyMap(), failedWindows = emptySet()) }
        readWindows(row.takeIf { current })
    }

    /** Reads the selected cell's windows that are still missing, from the sheet's Retry. */
    fun retryWindows() {
        val s = _state.value
        val row = s.selected ?: return
        if (windowsJob?.isActive == true) return
        val missing = FeedWindow.entries.filter { it !in s.selectedWindows }
        if (missing.isEmpty()) return
        _state.update { it.copy(failedWindows = emptySet()) }
        readWindows(row, missing)
    }

    fun dismissFirstRun() {
        if (!_state.value.firstRun) return
        saved = saved.copy(firstRunDone = true)
        persist()
        _state.update { it.copy(firstRun = false) }
    }

    private fun persist() {
        val snapshot = saved
        scope.launch { catching { prefs.save(snapshot) } }
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
            catching {
                val cover = ViewportCover.cover(h3, r.bounds, res)
                feed.cells(p, window, res, cover.ranges)
            }.getOrNull()
        }
        val rows = table?.rows.orEmpty()
        val dictionary = table?.info?.properties.orEmpty().ifEmpty { current.dictionary }
        val ranked = PropertyRanking.rank(rows, dictionary)
        val wanted = current.property?.id ?: saved.property
        val property = ranked.firstOrNull { it.id == wanted } ?: dictionary.firstOrNull { it.id == wanted && ranked.isEmpty() } ?: ranked.firstOrNull()
        // Read after the suspended feed read, so a tap made while it ran is not reverted.
        val selected = _state.value.selected?.let { s -> rows.firstOrNull { it.h3 == s.h3 } }
        val sameWindows = selected != null && windowsFor == Triple(selected.h3, window, res)
        rowsWindow = window
        _state.update {
            it.copy(
                loading = false,
                isSample = table?.info?.sample ?: feed.isSample,
                dictionary = dictionary,
                ranked = ranked,
                rows = rows,
                selected = selected,
                selectedWindows = when {
                    selected == null -> emptyMap()
                    sameWindows -> it.selectedWindows + (window to selected)
                    else -> mapOf(window to selected)
                },
                failedWindows = if (sameWindows) it.failedWindows - window else emptySet(),
                res = res,
                newestUpdateEpochMs = rows.maxOfOrNull { row -> row.updatedEpochMs },
            )
        }
        if (!sameWindows) {
            readWindows(selected)
        } else if (windowsJob?.isActive != true) {
            // A window whose read failed has no key. The pan reads it again, only that one.
            val missing = FeedWindow.entries.filter { it !in _state.value.selectedWindows }
            if (missing.isNotEmpty()) {
                _state.update { it.copy(failedWindows = emptySet()) }
                readWindows(selected, missing)
            }
        }
        restyle(property?.id)
    }

    /**
     * Reads [row]'s cell in the two windows not on screen, one small read each, the same anonymous
     * feed the map uses. A newer selection cancels it, and a result for an older cell, window or
     * resolution is dropped, so it never lands on the cell selected since. Each window lands as soon
     * as it is read. A read that fails is tried again after 1 s and then 2 s. One that still fails
     * stays out of the map and goes into [DiscoverUiState.failedWindows], and the next pan or the
     * sheet's Retry reads [only] the missing windows again.
     */
    private fun readWindows(row: CellRow?, only: List<FeedWindow>? = null) {
        windowsJob?.cancel()
        windowsJob = null
        windowsFor = null
        row ?: return
        val p = partition ?: return
        val current = _state.value
        val window = current.window
        val res = current.res
        windowsFor = Triple(row.h3, window, res)
        windowsJob = scope.launch {
            val wanted = only ?: FeedWindow.entries.filter { it != window }
            for (w in wanted) {
                val read = withContext(io) {
                    var result = catching { readCell(p, w, res, row.h3) }
                    for (wait in RETRY_DELAYS_MS) {
                        if (result.isSuccess) break
                        delay(wait)
                        result = catching { readCell(p, w, res, row.h3) }
                    }
                    result
                }
                ensureActive()
                _state.update {
                    if (it.selected?.h3 != row.h3 || it.window != window || it.res != res) return@update it
                    read.fold(
                        onSuccess = { r -> it.copy(selectedWindows = it.selectedWindows + (w to r), failedWindows = it.failedWindows - w) },
                        onFailure = { _ -> it.copy(failedWindows = it.failedWindows + w) },
                    )
                }
            }
        }
    }

    private suspend fun readCell(p: FeedPartition, w: FeedWindow, res: Int, cell: Long): CellRow? =
        feed.cells(p, w, res, listOf(cell..cell)).rows.firstOrNull { it.h3 == cell }

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

        /** The waits before the second and third try of a failed window read. */
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

        /** [runCatching] that lets cancellation through, so a cancelled read stops instead of reading on. */
        inline fun <T> catching(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
